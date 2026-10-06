package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.RefundRequestRepository;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PHASE 0 — split-payment regression contract.
 *
 * <p>Audit C3/C4 and BUG-03/BUG-04: a single booking can legitimately receive two
 * Stripe payments (an on-site ADVANCE and a later REMAINING), but {@code Booking}
 * keeps only one {@code paymentTransactionId}. The second payment overwrites the
 * first, so the original captured PaymentIntent is no longer addressable and a full
 * split refund cannot be reconciled.
 *
 * <p>These tests encode the required future property — both captured payment
 * references must remain individually addressable — using a test-local proxy
 * ({@link #capturedReferences}) for the not-yet-existing {@code PaymentTransaction}
 * ledger. They currently fail (only one reference survives) and must pass after
 * Phase 1 introduces the ledger. No production code is changed here.
 */
class SplitPaymentRegressionTest {

    private BookingRepository bookings;
    private InvoiceService invoiceService;
    private RemoteSessionProvisioningService remoteSessionProvisioningService;
    private UkEarlyServiceConsentService ukEarlyServiceConsentService;
    private RefundRequestRepository refundRequestRepository;
    private NotificationService notificationService;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        bookings = mock(BookingRepository.class);
        invoiceService = mock(InvoiceService.class);
        remoteSessionProvisioningService = mock(RemoteSessionProvisioningService.class);
        ukEarlyServiceConsentService = mock(UkEarlyServiceConsentService.class);
        refundRequestRepository = mock(RefundRequestRepository.class);
        notificationService = mock(NotificationService.class);
        service = new PaymentService(bookings, invoiceService, remoteSessionProvisioningService,
                ukEarlyServiceConsentService, refundRequestRepository, notificationService);
        when(bookings.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(remoteSessionProvisioningService.provisionAfterPayment(anyLong()))
                .thenAnswer(invocation -> bookings.findById(invocation.getArgument(0)).orElse(null));
    }

    /**
     * Test-local stand-in for the Phase 1 payment ledger: the set of captured Stripe
     * session references that remain independently addressable from the booking.
     */
    private Set<String> capturedReferences(Booking booking) {
        Set<String> references = new LinkedHashSet<>();
        if (booking.getPaymentTransactionId() != null) {
            references.add(booking.getPaymentTransactionId());
        }
        return references;
    }

    private Booking onsiteBooking() {
        Booking booking = new Booking();
        booking.setId(1L);
        booking.setCustomerId(5L);
        booking.setServiceMode(ServiceMode.ONSITE);
        booking.setPaymentStatus("PENDING");
        booking.setCurrency("USD");
        booking.setAdvanceAmount(30.0);
        booking.setRemainingAmount(70.0);
        booking.setTotalAmount(100.0);
        booking.setPaidAmount(0.0);
        booking.setPaymentType("ADVANCE_PAYMENT");
        booking.setBookingStatus(BookingStatus.PENDING);
        return booking;
    }

    private Session session(String id, long amountTotal, String currency, Long bookingId, String paymentType) {
        Session session = new Session();
        session.setId(id);
        session.setPaymentStatus("paid");
        session.setAmountTotal(amountTotal);
        session.setCurrency(currency);
        session.setMetadata(Map.of("bookingId", String.valueOf(bookingId), "paymentType", paymentType));
        return session;
    }

    private Booking payAdvanceThenRemaining(Booking booking) {
        when(bookings.findById(1L)).thenReturn(Optional.of(booking));

        service.applyCompletedCheckoutSession(session("sess_advance_A", 3000L, "usd", 1L, "ADVANCE"), null);

        // completeService() would move the booking to BALANCE_PENDING before the
        // remaining charge becomes due, so reproduce that intermediate state.
        booking.setPaymentStatus("BALANCE_PENDING");
        bookings.save(booking);

        service.applyCompletedCheckoutSession(session("sess_remaining_B", 7000L, "usd", 1L, "REMAINING"), null);
        return booking;
    }

    @Tag("expected-failure")
    @Test
    void advanceAndRemainingPaymentsMustBothRemainIndividuallyAddressable() {
        Booking booking = payAdvanceThenRemaining(onsiteBooking());

        Set<String> references = capturedReferences(booking);
        assertTrue(references.contains("sess_advance_A"),
                "the ADVANCE capture must remain addressable after the REMAINING capture; stored references=" + references);
        assertTrue(references.contains("sess_remaining_B"),
                "the REMAINING capture must be addressable; stored references=" + references);
    }

    @Tag("expected-failure")
    @Test
    void fullSplitRefundMustBeReconcilableAcrossEveryCapturedPayment() {
        Booking booking = payAdvanceThenRemaining(onsiteBooking());

        assertEquals(100.0, booking.getPaidAmount(), "booking aggregate paid amount should be the full 100");
        assertEquals(2, capturedReferences(booking).size(),
                "a full 100 refund must be reconcilable against the two actual captures (30 + 70), "
                        + "not a single overwritten reference");
    }
}
