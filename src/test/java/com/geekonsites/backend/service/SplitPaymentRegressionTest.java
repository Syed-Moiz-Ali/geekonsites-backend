package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.PaymentTransaction;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.PaymentTransactionStatus;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PHASE 1 — split-payment contract (audit C3/C4, BUG-03/BUG-04), now satisfied by the
 * {@link PaymentTransaction} ledger.
 *
 * <p>An on-site booking legitimately receives an ADVANCE and later a REMAINING payment.
 * Both captures must remain independently addressable so a full refund can be
 * reconciled across them. Previously the second payment overwrote the first
 * {@code Booking.paymentTransactionId}.
 */
class SplitPaymentRegressionTest extends Phase0IntegrationTestSupport {

    @Autowired PaymentService paymentService;

    private Booking onsiteBooking() {
        Booking booking = saveBooking(999L, ServiceMode.ONSITE, "PENDING", BookingStatus.PENDING);
        booking.setAdvanceAmount(30.0);
        booking.setRemainingAmount(70.0);
        booking.setTotalAmount(100.0);
        booking.setPaidAmount(0.0);
        return bookings.save(booking);
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

    private void payAdvanceThenRemaining(Booking booking) {
        paymentService.applyCompletedCheckoutSession(
                session("sess_advance_A", 3000L, "usd", booking.getId(), "ADVANCE"), null);

        Booking afterAdvance = bookings.findById(booking.getId()).orElseThrow();
        // completeService() moves the booking to BALANCE_PENDING before the remaining
        // charge becomes due; reproduce that intermediate state.
        afterAdvance.setPaymentStatus("BALANCE_PENDING");
        bookings.save(afterAdvance);

        paymentService.applyCompletedCheckoutSession(
                session("sess_remaining_B", 7000L, "usd", booking.getId(), "REMAINING"), null);
    }

    @Test
    void advanceAndRemainingPaymentsMustBothRemainIndividuallyAddressable() {
        Booking booking = onsiteBooking();
        payAdvanceThenRemaining(booking);

        List<PaymentTransaction> transactions =
                paymentTransactions.findByBookingIdOrderByCreatedAtAsc(booking.getId());

        assertEquals(2, transactions.size(), "both payment transactions must persist");
        assertTrue(transactions.stream().anyMatch(t ->
                        "sess_advance_A".equals(t.getCheckoutSessionId())
                                && t.getStatus() == PaymentTransactionStatus.SUCCEEDED
                                && t.getAmountMinor() == 3000L),
                "the ADVANCE transaction must remain addressable after the REMAINING capture");
        assertTrue(transactions.stream().anyMatch(t ->
                        "sess_remaining_B".equals(t.getCheckoutSessionId())
                                && t.getStatus() == PaymentTransactionStatus.SUCCEEDED
                                && t.getAmountMinor() == 7000L),
                "the REMAINING transaction must remain addressable");
    }

    @Test
    void fullSplitRefundMustBeReconcilableAcrossEveryCapturedPayment() {
        Booking booking = onsiteBooking();
        payAdvanceThenRemaining(booking);

        assertEquals(10000L, paymentTransactions.sumSuccessfulAmountMinorByBookingId(booking.getId()),
                "a full 100 refund must be reconcilable against the two actual captures (30 + 70)");
        assertEquals(100.0, bookings.findById(booking.getId()).orElseThrow().getPaidAmount());
    }
}
