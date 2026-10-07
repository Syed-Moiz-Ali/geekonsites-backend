package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.PaymentTransaction;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.PaymentProvider;
import com.geekonsites.backend.enums.PaymentTransactionStatus;
import com.geekonsites.backend.enums.PaymentType;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PHASE 4 — true concurrency tests using real threads against the same business resource.
 * Pessimistic row locks must keep each resource coherent under simultaneous requests.
 *
 * <p>Note: H2 is used here; PostgreSQL lock behaviour must be re-verified before production
 * (see Phase 4 report).
 */
class Phase4ConcurrencyIntegrationTest extends Phase0IntegrationTestSupport {

    @Autowired BookingService bookingService;
    @Autowired PaymentService paymentService;

    private Session session(String id, long amountTotal, String currency, Long bookingId, String paymentType) {
        Session session = new Session();
        session.setId(id);
        session.setPaymentStatus("paid");
        session.setAmountTotal(amountTotal);
        session.setCurrency(currency);
        session.setMetadata(Map.of("bookingId", String.valueOf(bookingId), "paymentType", paymentType));
        return session;
    }

    private List<Throwable> runConcurrently(List<Callable<?>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        List<Future<?>> futures = new ArrayList<>();
        for (Callable<?> task : tasks) {
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return task.call();
            }));
        }
        List<Throwable> errors = new ArrayList<>();
        for (Future<?> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
            } catch (Exception executionError) {
                errors.add(executionError.getCause() == null ? executionError : executionError.getCause());
            }
        }
        pool.shutdownNow();
        return errors;
    }

    @Test
    void concurrentPaymentFinalizationFinalizesExactlyOnce() throws Exception {
        Booking booking = saveBooking(999L, ServiceMode.REMOTE, "PENDING", BookingStatus.PENDING);
        booking.setTotalAmount(100.0);
        booking.setPaidAmount(0.0);
        Booking saved = bookings.save(booking);

        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setBookingId(saved.getId());
        transaction.setCustomerId(999L);
        transaction.setPaymentType(PaymentType.FULL);
        transaction.setProvider(PaymentProvider.STRIPE);
        transaction.setAmountMinor(10000L);
        transaction.setCurrency("USD");
        transaction.setCheckoutSessionId("cs_conc");
        transaction.setStatus(PaymentTransactionStatus.CHECKOUT_CREATED);
        paymentTransactions.save(transaction);

        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            tasks.add(() -> paymentService.applyCompletedCheckoutSession(
                    session("cs_conc", 10000L, "usd", saved.getId(), "FULL"), null));
        }
        runConcurrently(tasks);

        List<PaymentTransaction> rows = paymentTransactions.findByBookingIdOrderByCreatedAtAsc(saved.getId());
        assertEquals(1, rows.size(), "no duplicate ledger rows");
        assertEquals(PaymentTransactionStatus.SUCCEEDED, rows.get(0).getStatus());
        assertEquals(100.0, bookings.findById(saved.getId()).orElseThrow().getPaidAmount());
    }

    @Test
    void concurrentAssignmentLeavesOneCoherentAssignment() throws Exception {
        Booking booking = saveBooking(999L, ServiceMode.REMOTE, "PAID", BookingStatus.PAYMENT_COMPLETED);
        Technician first = saveTechnician("conc-tech-a-" + System.nanoTime() + "@example.com",
                "APPROVED", "AVAILABLE", "REMOTE_AND_ONSITE");
        Technician second = saveTechnician("conc-tech-b-" + System.nanoTime() + "@example.com",
                "APPROVED", "AVAILABLE", "REMOTE_AND_ONSITE");
        Long bookingId = booking.getId();

        List<Callable<?>> tasks = List.of(
                () -> bookingService.assignTechnician(bookingId, first.getId()),
                () -> bookingService.assignTechnician(bookingId, second.getId()));
        runConcurrently(tasks);

        Booking reloaded = bookings.findById(bookingId).orElseThrow();
        assertNotNull(reloaded.getTechnicianId(), "the booking must end with a technician");
        long busy = List.of(first.getId(), second.getId()).stream()
                .map(id -> technicians.findById(id).orElseThrow().getAvailabilityStatus())
                .filter("BUSY"::equals).count();
        assertEquals(1, busy, "exactly one technician must be BUSY after concurrent assignment");
    }

    @Test
    void concurrentAcceptVersusRejectResultsInOneCoherentState() throws Exception {
        Technician technician = saveTechnician("conc-tech-r-" + System.nanoTime() + "@example.com",
                "APPROVED", "BUSY", "REMOTE_ONLY");
        Booking booking = saveBookingForCustomer(999L, technician.getId(), ServiceMode.REMOTE, "PAID",
                BookingStatus.TECHNICIAN_ASSIGNED);
        Long bookingId = booking.getId();

        List<Callable<?>> tasks = List.of(
                () -> bookingService.technicianAcceptJob(bookingId, technician.getId()),
                () -> bookingService.technicianRejectJob(bookingId, technician.getId(), "unavailable"));
        runConcurrently(tasks);

        Booking reloaded = bookings.findById(bookingId).orElseThrow();
        Technician reloadedTechnician = technicians.findById(technician.getId()).orElseThrow();
        if (reloaded.getBookingStatus() == BookingStatus.TECHNICIAN_ACCEPTED) {
            assertEquals("BUSY", reloadedTechnician.getAvailabilityStatus());
        } else {
            assertEquals(BookingStatus.TECHNICIAN_REJECTED, reloaded.getBookingStatus());
            assertEquals("AVAILABLE", reloadedTechnician.getAvailabilityStatus());
        }
    }

    @Test
    void concurrentCompletionProducesOneCompletion() throws Exception {
        Technician technician = saveTechnician("conc-tech-c-" + System.nanoTime() + "@example.com",
                "APPROVED", "BUSY", "ONSITE_ONLY");
        Booking booking = saveBookingForCustomer(999L, technician.getId(), ServiceMode.ONSITE, "PAID",
                BookingStatus.SERVICE_STARTED);
        Long bookingId = booking.getId();

        List<Callable<?>> tasks = List.of(
                () -> bookingService.completeService(bookingId, technician.getId()),
                () -> bookingService.completeService(bookingId, technician.getId()));
        runConcurrently(tasks);

        Booking reloaded = bookings.findById(bookingId).orElseThrow();
        assertEquals(BookingStatus.SERVICE_COMPLETED, reloaded.getBookingStatus());
        assertNotNull(reloaded.getServiceCompletedAt());
        assertEquals("AVAILABLE", technicians.findById(technician.getId()).orElseThrow().getAvailabilityStatus());
    }

    @Test
    void failedFinalizationValidationLeavesNoPartialState() {
        Booking booking = saveBooking(999L, ServiceMode.REMOTE, "PENDING", BookingStatus.PENDING);
        booking.setTotalAmount(100.0);
        Booking saved = bookings.save(booking);

        // Amount mismatch must roll back: no ledger row persisted, booking unchanged.
        Exception error = null;
        try {
            paymentService.applyCompletedCheckoutSession(
                    session("cs_bad", 5000L, "usd", saved.getId(), "FULL"), null);
        } catch (Exception e) {
            error = e;
        }
        assertNotNull(error);
        assertTrue(paymentTransactions.findByBookingIdOrderByCreatedAtAsc(saved.getId()).isEmpty());
        assertEquals("PENDING", bookings.findById(saved.getId()).orElseThrow().getPaymentStatus());
    }
}
