package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.PaymentTransaction;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.PaymentProvider;
import com.geekonsites.backend.enums.PaymentTransactionStatus;
import com.geekonsites.backend.enums.PaymentType;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PHASE 1 — payment-ledger contract coverage (Step 25 A–J of the phase brief).
 *
 * <p>Verifies that the {@link PaymentTransaction} ledger is written at payment time,
 * that booking totals are derived from it, and that the shared webhook/confirmation
 * finalization path is idempotent and rejects mismatched amount, currency, booking or
 * payment type.
 */
class PaymentLedgerIntegrationTest extends Phase0IntegrationTestSupport {

    @Autowired PaymentService paymentService;

    private Booking remoteBooking(double total) {
        Booking booking = saveBooking(999L, ServiceMode.REMOTE, "PENDING", BookingStatus.PENDING);
        booking.setTotalAmount(total);
        booking.setPaidAmount(0.0);
        return bookings.save(booking);
    }

    private Booking onsiteBooking(double advance, double remaining, double total) {
        Booking booking = saveBooking(999L, ServiceMode.ONSITE, "PENDING", BookingStatus.PENDING);
        booking.setAdvanceAmount(advance);
        booking.setRemainingAmount(remaining);
        booking.setTotalAmount(total);
        booking.setPaidAmount(0.0);
        return bookings.save(booking);
    }

    private PaymentTransaction ledgerRow(Long bookingId, String sessionId, PaymentType type,
                                         long amountMinor, String currency, PaymentTransactionStatus status) {
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setBookingId(bookingId);
        transaction.setCustomerId(999L);
        transaction.setPaymentType(type);
        transaction.setProvider(PaymentProvider.STRIPE);
        transaction.setAmountMinor(amountMinor);
        transaction.setCurrency(currency);
        transaction.setCheckoutSessionId(sessionId);
        transaction.setStatus(status);
        return paymentTransactions.save(transaction);
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

    // A — FULL payment produces exactly one successful ledger row.
    @Test
    void fullPaymentCreatesSingleSuccessfulLedgerRow() {
        Booking booking = remoteBooking(100.0);

        paymentService.applyCompletedCheckoutSession(
                session("cs_full", 10000L, "usd", booking.getId(), "FULL"), null);

        var transactions = paymentTransactions.findByBookingIdOrderByCreatedAtAsc(booking.getId());
        assertEquals(1, transactions.size());
        assertEquals(PaymentTransactionStatus.SUCCEEDED, transactions.get(0).getStatus());
        assertEquals(10000L, transactions.get(0).getAmountMinor());
        assertEquals(100.0, bookings.findById(booking.getId()).orElseThrow().getPaidAmount());
    }

    // B — ADVANCE payment ledger row is preserved.
    @Test
    void advancePaymentCreatesLedgerRow() {
        Booking booking = onsiteBooking(30.0, 70.0, 100.0);

        paymentService.applyCompletedCheckoutSession(
                session("cs_advance", 3000L, "usd", booking.getId(), "ADVANCE"), null);

        var transactions = paymentTransactions.findByBookingIdOrderByCreatedAtAsc(booking.getId());
        assertEquals(1, transactions.size());
        assertEquals(PaymentType.ADVANCE, transactions.get(0).getPaymentType());
        assertEquals(3000L, transactions.get(0).getAmountMinor());
        assertEquals("PARTIALLY_PAID", bookings.findById(booking.getId()).orElseThrow().getPaymentStatus());
    }

    // C — REMAINING payment creates a second ledger row (does not overwrite the first).
    @Test
    void remainingPaymentCreatesSecondLedgerRow() {
        Booking booking = onsiteBooking(30.0, 70.0, 100.0);
        paymentService.applyCompletedCheckoutSession(
                session("cs_advance", 3000L, "usd", booking.getId(), "ADVANCE"), null);

        Booking afterAdvance = bookings.findById(booking.getId()).orElseThrow();
        afterAdvance.setPaymentStatus("BALANCE_PENDING");
        bookings.save(afterAdvance);

        paymentService.applyCompletedCheckoutSession(
                session("cs_remaining", 7000L, "usd", booking.getId(), "REMAINING"), null);

        assertEquals(2, paymentTransactions.findByBookingIdOrderByCreatedAtAsc(booking.getId()).size());
        assertEquals(10000L, paymentTransactions.sumSuccessfulAmountMinorByBookingId(booking.getId()));
    }

    // E — duplicate webhook delivery is idempotent.
    @Test
    void duplicateWebhookDoesNotDoubleCountPayment() {
        Booking booking = remoteBooking(100.0);
        ledgerRow(booking.getId(), "cs_dup", PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);

        Session session = session("cs_dup", 10000L, "usd", booking.getId(), "FULL");
        paymentService.applyCompletedCheckoutSession(session, null);
        paymentService.applyCompletedCheckoutSession(session, null);

        var transactions = paymentTransactions.findByBookingIdOrderByCreatedAtAsc(booking.getId());
        assertEquals(1, transactions.size(), "a replayed webhook must not create a second ledger row");
        assertEquals(10000L, paymentTransactions.sumSuccessfulAmountMinorByBookingId(booking.getId()));
        assertEquals(100.0, bookings.findById(booking.getId()).orElseThrow().getPaidAmount(),
                "paid amount must not be doubled by a webhook replay");
    }

    // F — webhook and confirmation fallback converge on one finalization path.
    @Test
    void webhookAndConfirmationFallbackFinalizeTransactionOnce() {
        Booking booking = remoteBooking(100.0);
        ledgerRow(booking.getId(), "cs_both", PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);

        Session session = session("cs_both", 10000L, "usd", booking.getId(), "FULL");
        paymentService.applyCompletedCheckoutSession(session, null);                 // webhook
        paymentService.applyCompletedCheckoutSession(session, booking.getCustomerId()); // fallback

        assertEquals(1, paymentTransactions.findByBookingIdOrderByCreatedAtAsc(booking.getId()).stream()
                .filter(t -> t.getStatus() == PaymentTransactionStatus.SUCCEEDED).count());
    }

    // G — Stripe amount differing from the ledger expected amount is rejected.
    @Test
    void amountMismatchAgainstLedgerIsRejected() {
        Booking booking = remoteBooking(100.0);
        ledgerRow(booking.getId(), "cs_amt", PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);

        assertThrows(RuntimeException.class, () -> paymentService.applyCompletedCheckoutSession(
                session("cs_amt", 5000L, "usd", booking.getId(), "FULL"), null));

        assertTrue(paymentTransactions.findByBookingIdOrderByCreatedAtAsc(booking.getId()).stream()
                .noneMatch(t -> t.getStatus() == PaymentTransactionStatus.SUCCEEDED));
        assertEquals("PENDING", bookings.findById(booking.getId()).orElseThrow().getPaymentStatus());
    }

    // H — Stripe currency differing from the ledger currency is rejected.
    @Test
    void currencyMismatchAgainstLedgerIsRejected() {
        Booking booking = remoteBooking(100.0);
        ledgerRow(booking.getId(), "cs_cur", PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);

        assertThrows(RuntimeException.class, () -> paymentService.applyCompletedCheckoutSession(
                session("cs_cur", 10000L, "gbp", booking.getId(), "FULL"), null));
    }

    // I — metadata pointing at the wrong booking is rejected.
    @Test
    void wrongBookingMetadataIsRejected() {
        Booking owner = remoteBooking(100.0);
        Booking other = remoteBooking(100.0);
        ledgerRow(owner.getId(), "cs_meta", PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);

        // Session session id belongs to `owner`'s ledger row, but metadata claims `other`.
        assertThrows(RuntimeException.class, () -> paymentService.applyCompletedCheckoutSession(
                session("cs_meta", 10000L, "usd", other.getId(), "FULL"), null));
    }

    // J — metadata payment type contradicting the ledger row is rejected.
    @Test
    void wrongPaymentTypeMetadataIsRejected() {
        Booking booking = onsiteBooking(30.0, 70.0, 100.0);
        ledgerRow(booking.getId(), "cs_type", PaymentType.ADVANCE, 3000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);

        assertThrows(RuntimeException.class, () -> paymentService.applyCompletedCheckoutSession(
                session("cs_type", 3000L, "usd", booking.getId(), "FULL"), null));
    }
}
