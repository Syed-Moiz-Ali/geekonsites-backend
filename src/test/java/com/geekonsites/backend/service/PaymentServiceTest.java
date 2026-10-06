package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.RefundRequestRepository;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers PaymentService.applyCompletedCheckoutSession - the single method
 * that decides whether a Stripe session is trusted and applied to a
 * booking, reached from both the webhook and the authenticated
 * confirm-checkout-session fallback. No existing test covered this before.
 *
 * Focus: the amount/currency come from Stripe's own session object and the
 * booking's server-computed pricing, never from anything the frontend
 * could have sent; ADVANCE/REMAINING/FULL apply the correct booking state
 * for onsite vs remote; and replaying the same Stripe session id (a
 * webhook retry, or a refreshed success page) is a safe no-op rather than
 * re-charging or re-applying booking-state changes.
 */
class PaymentServiceTest {

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
        // finalizePaidBooking looks the booking back up (or, once fully PAID,
        // routes through remoteSessionProvisioningService) rather than
        // returning the saved instance directly - mirror that with the same
        // in-memory booking so assertions can inspect the real end state.
        when(remoteSessionProvisioningService.provisionAfterPayment(anyLong()))
                .thenAnswer(invocation -> bookings.findById(invocation.getArgument(0)).orElse(null));
    }

    private Booking onsiteBooking(String paymentStatus, double advance, double remaining, double total, String currency) {
        Booking booking = new Booking();
        booking.setId(1L);
        booking.setCustomerId(5L);
        booking.setServiceMode(ServiceMode.ONSITE);
        booking.setPaymentStatus(paymentStatus);
        booking.setAdvanceAmount(advance);
        booking.setRemainingAmount(remaining);
        booking.setTotalAmount(total);
        booking.setCurrency(currency);
        return booking;
    }

    private Booking remoteBooking(String paymentStatus, double total, String currency) {
        Booking booking = new Booking();
        booking.setId(2L);
        booking.setCustomerId(5L);
        booking.setServiceMode(ServiceMode.REMOTE);
        booking.setPaymentStatus(paymentStatus);
        booking.setTotalAmount(total);
        booking.setCurrency(currency);
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

    @Test
    void advancePaymentForOnsiteBookingSetsPartiallyPaidAndAssignmentPending() {
        Booking booking = onsiteBooking("PENDING", 90.0, 210.0, 300.0, "USD");
        when(bookings.findById(1L)).thenReturn(Optional.of(booking));
        Session session = session("sess_advance", 9000L, "usd", 1L, "ADVANCE");

        Booking result = service.applyCompletedCheckoutSession(session, null);

        assertEquals("PARTIALLY_PAID", result.getPaymentStatus());
        assertEquals(BookingStatus.ASSIGNMENT_PENDING, result.getBookingStatus());
        assertEquals(90.0, result.getPaidAmount());
        assertEquals("sess_advance", result.getPaymentTransactionId());
        verify(invoiceService).generateInvoiceFromBooking(1L);
        verify(remoteSessionProvisioningService, never()).provisionAfterPayment(anyLong());
    }

    @Test
    void remainingPaymentForOnsiteBookingSetsPaidAndServiceCompleted() {
        Booking booking = onsiteBooking("BALANCE_PENDING", 90.0, 210.0, 300.0, "GBP");
        when(bookings.findById(1L)).thenReturn(Optional.of(booking));
        Session session = session("sess_remaining", 21000L, "gbp", 1L, "REMAINING");

        Booking result = service.applyCompletedCheckoutSession(session, null);

        assertEquals("PAID", result.getPaymentStatus());
        assertEquals(0.0, result.getRemainingAmount());
        assertEquals(300.0, result.getPaidAmount());
        assertEquals(BookingStatus.SERVICE_COMPLETED, result.getBookingStatus());
    }

    @Test
    void fullPaymentForRemoteBookingSetsPaidAndPaymentCompleted() {
        Booking booking = remoteBooking("PENDING", 150.0, "USD");
        when(bookings.findById(2L)).thenReturn(Optional.of(booking));
        Session session = session("sess_full", 15000L, "usd", 2L, "FULL");

        Booking result = service.applyCompletedCheckoutSession(session, null);

        assertEquals("PAID", result.getPaymentStatus());
        assertEquals(BookingStatus.PAYMENT_COMPLETED, result.getBookingStatus());
        assertEquals(150.0, result.getPaidAmount());
        verify(remoteSessionProvisioningService).provisionAfterPayment(2L);
    }

    @Test
    void mismatchedStripeAmountIsRejectedEvenThoughStatusIsPaid() {
        Booking booking = remoteBooking("PENDING", 150.0, "USD");
        when(bookings.findById(2L)).thenReturn(Optional.of(booking));
        // Stripe confirms "paid", but for less than the server-computed price -
        // this must never be trusted just because paymentStatus == "paid".
        Session session = session("sess_underpaid", 5000L, "usd", 2L, "FULL");

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.applyCompletedCheckoutSession(session, null));

        assertEquals("Stripe payment amount does not match the booking", exception.getMessage());
        assertEquals("PENDING", booking.getPaymentStatus(), "booking must not be marked paid on an amount mismatch");
        verify(bookings, never()).save(any(Booking.class));
    }

    @Test
    void mismatchedStripeCurrencyIsRejected() {
        Booking booking = onsiteBooking("BALANCE_PENDING", 90.0, 210.0, 300.0, "GBP");
        when(bookings.findById(1L)).thenReturn(Optional.of(booking));
        // Right amount, wrong currency (e.g. a stale/tampered session).
        Session session = session("sess_wrong_currency", 21000L, "usd", 1L, "REMAINING");

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.applyCompletedCheckoutSession(session, null));

        assertEquals("Stripe payment currency does not match the booking", exception.getMessage());
        verify(bookings, never()).save(any(Booking.class));
    }

    @Test
    void replayingTheSameStripeSessionIsIdempotentAndNeverDoubleAppliesOrCharges() {
        Booking booking = remoteBooking("PAID", 150.0, "USD");
        booking.setPaymentTransactionId("sess_already_applied");
        booking.setBookingStatus(BookingStatus.PAYMENT_COMPLETED);
        booking.setPaidAmount(150.0);
        when(bookings.findById(2L)).thenReturn(Optional.of(booking));
        // Same session id as already recorded - a Stripe webhook retry, or the
        // customer refreshing the success page.
        Session session = session("sess_already_applied", 15000L, "usd", 2L, "FULL");

        service.applyCompletedCheckoutSession(session, null);

        // No booking-state mutation, no second save - purely re-notifies and
        // returns the already-finalized booking.
        verify(bookings, never()).save(any(Booking.class));
        verify(notificationService, times(1)).createPaymentSuccessNotification(any(Booking.class), anyString(), anyString());
    }

    @Test
    void ukGbpOnsiteAdvancePaymentIsAcceptedWithMatchingCurrency() {
        Booking booking = onsiteBooking("PENDING", 60.0, 140.0, 200.0, "GBP");
        when(bookings.findById(1L)).thenReturn(Optional.of(booking));
        Session session = session("sess_uk_advance", 6000L, "gbp", 1L, "ADVANCE");

        Booking result = service.applyCompletedCheckoutSession(session, null);

        assertEquals("PARTIALLY_PAID", result.getPaymentStatus());
        assertEquals("GBP", result.getCurrency());
    }

    @Test
    void anotherCustomersConfirmAttemptIsRejected() {
        Booking booking = remoteBooking("PENDING", 150.0, "USD");
        when(bookings.findById(2L)).thenReturn(Optional.of(booking));
        Session session = session("sess_full", 15000L, "usd", 2L, "FULL");

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.applyCompletedCheckoutSession(session, 999L));

        assertEquals("You cannot confirm another customer's payment", exception.getMessage());
        verify(bookings, never()).save(any(Booking.class));
    }
}
