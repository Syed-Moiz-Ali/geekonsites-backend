package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.StripeCheckoutResponse;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.PaymentTransaction;
import com.geekonsites.backend.entity.RefundRequest;
import com.geekonsites.backend.enums.PaymentProvider;
import com.geekonsites.backend.enums.PaymentTransactionStatus;
import com.geekonsites.backend.enums.PaymentType;
import com.geekonsites.backend.enums.RefundStatus;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.PaymentRefundRepository;
import com.geekonsites.backend.repository.PaymentTransactionRepository;
import com.geekonsites.backend.repository.RefundRequestRepository;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PHASE 3 — Stripe payment-lifecycle hardening tests.
 *
 * <p>Unit-level, using mocks for repositories and a stubbed {@link StripeCheckoutGateway}
 * so checkout creation is exercised without a live Stripe account.
 */
class StripePaymentHardeningTest {

    private static final long BOOKING_ID = 1L;
    private static final String WEBHOOK_SECRET = "whsec_test_secret";

    private BookingRepository bookings;
    private PaymentTransactionRepository transactions;
    private PaymentRefundRepository paymentRefunds;
    private RefundRequestRepository refundRequests;
    private InvoiceService invoices;
    private RemoteSessionProvisioningService remote;
    private UkEarlyServiceConsentService consent;
    private NotificationService notifications;
    private StripeCheckoutGateway gateway;
    private PaymentService service;

    private final AtomicLong ids = new AtomicLong(1);
    private final List<PaymentTransaction> savedTransactions = new ArrayList<>();
    private final Map<Long, PaymentTransaction> transactionsById = new HashMap<>();
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private Booking booking;

    @BeforeEach
    void setUp() {
        bookings = mock(BookingRepository.class);
        transactions = mock(PaymentTransactionRepository.class);
        paymentRefunds = mock(PaymentRefundRepository.class);
        refundRequests = mock(RefundRequestRepository.class);
        invoices = mock(InvoiceService.class);
        remote = mock(RemoteSessionProvisioningService.class);
        consent = mock(UkEarlyServiceConsentService.class);
        notifications = mock(NotificationService.class);
        gateway = mock(StripeCheckoutGateway.class);

        when(bookings.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactions.save(any(PaymentTransaction.class))).thenAnswer(inv -> {
            PaymentTransaction t = inv.getArgument(0);
            if (t.getId() == null) t.setId(ids.getAndIncrement());
            transactionsById.put(t.getId(), t);
            if (!savedTransactions.contains(t)) savedTransactions.add(t);
            return t;
        });
        when(transactions.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(transactionsById.get(inv.getArgument(0))));
        when(transactions.findByIdForUpdate(anyLong())).thenAnswer(inv -> Optional.ofNullable(transactionsById.get(inv.getArgument(0))));
        when(bookings.findByIdForUpdate(anyLong())).thenAnswer(inv -> bookings.findById(inv.getArgument(0)));
        when(paymentRefunds.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(refundRequests.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactions.findByCheckoutSessionId(anyString())).thenReturn(Optional.empty());
        when(transactions.sumSuccessfulAmountMinorByBookingId(anyLong())).thenReturn(0L);
        when(remote.provisionAfterPayment(anyLong()))
                .thenAnswer(inv -> bookings.findById(inv.getArgument(0)).orElse(null));
        when(gateway.createCheckoutSession(any())).thenAnswer(inv -> {
            StripeCheckoutGateway.CheckoutRequest r = inv.getArgument(0);
            return new StripeCheckoutGateway.CheckoutSession(
                    "cs_" + r.paymentTransactionId(), "https://pay/" + r.paymentTransactionId(),
                    LocalDateTime.now().plusHours(1));
        });

        service = new PaymentService(bookings, invoices, remote, consent, refundRequests, notifications,
                transactions, paymentRefunds, new BookingStateMachine(),
                new PaymentTransactionStateMachine(), new PaymentRefundStateMachine(), gateway,
                eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));

        ReflectionTestUtils.setField(service, "stripeSecretKey", "sk_test");
        ReflectionTestUtils.setField(service, "successUrl", "https://gos/success");
        ReflectionTestUtils.setField(service, "cancelUrl", "https://gos/cancel");
        ReflectionTestUtils.setField(service, "webhookSecret", WEBHOOK_SECRET);
    }

    // ------------------------------------------------------------------ fixtures

    private Booking booking(long id, ServiceMode mode, String paymentStatus, double total) {
        Booking b = new Booking();
        b.setId(id);
        b.setCustomerId(7L);
        b.setServiceMode(mode);
        b.setCurrency("USD");
        b.setTotalAmount(total);
        b.setAdvanceAmount(Math.round(total * 0.3 * 100.0) / 100.0);
        b.setRemainingAmount(total - b.getAdvanceAmount());
        b.setPaidAmount(0.0);
        b.setPaymentStatus(paymentStatus);
        return b;
    }

    private void givenBooking(Booking b) {
        this.booking = b;
        when(bookings.findById(b.getId())).thenReturn(Optional.of(b));
    }

    private PaymentTransaction transaction(Long id, Long bookingId, PaymentType type, long amountMinor,
                                           String currency, PaymentTransactionStatus status) {
        PaymentTransaction t = new PaymentTransaction();
        t.setId(id);
        t.setBookingId(bookingId);
        t.setCustomerId(7L);
        t.setPaymentType(type);
        t.setProvider(PaymentProvider.STRIPE);
        t.setAmountMinor(amountMinor);
        t.setCurrency(currency);
        t.setStatus(status);
        return t;
    }

    private Session session(String id, String paymentStatus, long amountTotal, String currency,
                            Long bookingId, String paymentType, String paymentIntentId, String transactionId) {
        Session s = new Session();
        s.setId(id);
        s.setPaymentStatus(paymentStatus);
        s.setAmountTotal(amountTotal);
        s.setCurrency(currency);
        s.setPaymentIntent(paymentIntentId);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("bookingId", String.valueOf(bookingId));
        metadata.put("paymentType", paymentType);
        metadata.put("paymentTransactionId", transactionId);
        s.setMetadata(metadata);
        return s;
    }

    private Session paid(String id, long amountTotal, String currency, Long bookingId, String paymentType,
                         String paymentIntentId, String transactionId) {
        return session(id, "paid", amountTotal, currency, bookingId, paymentType, paymentIntentId, transactionId);
    }

    private String sign(String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(hash);
    }

    // -------------------------------------------------------------- finalization

    @Test
    void paidCompletedSessionFinalizesPayment() {
        Booking b = booking(BOOKING_ID, ServiceMode.REMOTE, "PENDING", 100.0);
        givenBooking(b);
        PaymentTransaction t = transaction(100L, BOOKING_ID, PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);
        when(transactions.findByCheckoutSessionId("cs_ok")).thenReturn(Optional.of(t));

        service.applyCompletedCheckoutSession(
                paid("cs_ok", 10000L, "usd", BOOKING_ID, "FULL", "pi_1", "100"), null);

        assertEquals(PaymentTransactionStatus.SUCCEEDED, t.getStatus());
        assertEquals("PAID", b.getPaymentStatus());
        assertEquals(100.0, b.getPaidAmount());
        verify(notifications).createPaymentSuccessNotification(any(Booking.class), anyString(), anyString());
    }

    @Test
    void unpaidCompletedSessionDoesNotFinalizeOrMutate() {
        Booking b = booking(BOOKING_ID, ServiceMode.REMOTE, "PENDING", 100.0);
        givenBooking(b);

        assertThrows(RuntimeException.class, () -> service.applyCompletedCheckoutSession(
                session("cs_unpaid", "unpaid", 10000L, "usd", BOOKING_ID, "FULL", "pi_1", "100"), null));

        verify(transactions, never()).save(any());
        verify(notifications, never()).createPaymentSuccessNotification(any(), anyString(), anyString());
    }

    @Test
    void wrongBookingMetadataIsRejected() {
        Booking b = booking(BOOKING_ID, ServiceMode.REMOTE, "PENDING", 100.0);
        givenBooking(b);
        PaymentTransaction t = transaction(100L, 999L, PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);
        when(transactions.findByCheckoutSessionId("cs_wrong")).thenReturn(Optional.of(t));

        assertThrows(RuntimeException.class, () -> service.applyCompletedCheckoutSession(
                paid("cs_wrong", 10000L, "usd", BOOKING_ID, "FULL", "pi_1", "100"), null));
        verify(transactions, never()).save(any());
    }

    @Test
    void wrongMetadataTransactionIdIsRejected() {
        Booking b = booking(BOOKING_ID, ServiceMode.REMOTE, "PENDING", 100.0);
        givenBooking(b);
        PaymentTransaction other = transaction(500L, 999L, PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.INITIATED);
        when(transactions.findById(500L)).thenReturn(Optional.of(other));

        assertThrows(RuntimeException.class, () -> service.applyCompletedCheckoutSession(
                paid("cs_unknown", 10000L, "usd", BOOKING_ID, "FULL", "pi_1", "500"), null));
        verify(transactions, never()).save(any());
    }

    @Test
    void wrongPaymentTypeMetadataIsRejected() {
        Booking b = booking(BOOKING_ID, ServiceMode.ONSITE, "PENDING", 100.0);
        givenBooking(b);
        PaymentTransaction t = transaction(100L, BOOKING_ID, PaymentType.ADVANCE, 3000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);
        when(transactions.findByCheckoutSessionId("cs_type")).thenReturn(Optional.of(t));

        assertThrows(RuntimeException.class, () -> service.applyCompletedCheckoutSession(
                paid("cs_type", 3000L, "usd", BOOKING_ID, "FULL", "pi_1", "100"), null));
        verify(transactions, never()).save(any());
    }

    @Test
    void amountMismatchIsRejected() {
        Booking b = booking(BOOKING_ID, ServiceMode.REMOTE, "PENDING", 100.0);
        givenBooking(b);
        PaymentTransaction t = transaction(100L, BOOKING_ID, PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);
        when(transactions.findByCheckoutSessionId("cs_amt")).thenReturn(Optional.of(t));

        assertThrows(RuntimeException.class, () -> service.applyCompletedCheckoutSession(
                paid("cs_amt", 5000L, "usd", BOOKING_ID, "FULL", "pi_1", "100"), null));
        verify(transactions, never()).save(any());
    }

    @Test
    void currencyMismatchIsRejected() {
        Booking b = booking(BOOKING_ID, ServiceMode.REMOTE, "PENDING", 100.0);
        givenBooking(b);
        PaymentTransaction t = transaction(100L, BOOKING_ID, PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);
        when(transactions.findByCheckoutSessionId("cs_cur")).thenReturn(Optional.of(t));

        assertThrows(RuntimeException.class, () -> service.applyCompletedCheckoutSession(
                paid("cs_cur", 10000L, "gbp", BOOKING_ID, "FULL", "pi_1", "100"), null));
        verify(transactions, never()).save(any());
    }

    @Test
    void changedPaymentIntentIdIsRejected() {
        Booking b = booking(BOOKING_ID, ServiceMode.REMOTE, "PENDING", 100.0);
        givenBooking(b);
        PaymentTransaction t = transaction(100L, BOOKING_ID, PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);
        t.setPaymentIntentId("pi_original");
        when(transactions.findByCheckoutSessionId("cs_pi")).thenReturn(Optional.of(t));

        assertThrows(RuntimeException.class, () -> service.applyCompletedCheckoutSession(
                paid("cs_pi", 10000L, "usd", BOOKING_ID, "FULL", "pi_different", "100"), null));
        assertEquals("pi_original", t.getPaymentIntentId());
        verify(transactions, never()).save(any());
    }

    @Test
    void lowercaseStripeCurrencyMatchesStoredUppercase() {
        Booking b = booking(BOOKING_ID, ServiceMode.REMOTE, "PENDING", 100.0);
        givenBooking(b);
        PaymentTransaction t = transaction(100L, BOOKING_ID, PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);
        when(transactions.findByCheckoutSessionId("cs_lc")).thenReturn(Optional.of(t));

        service.applyCompletedCheckoutSession(
                paid("cs_lc", 10000L, "usd", BOOKING_ID, "FULL", "pi_1", "100"), null);

        assertEquals(PaymentTransactionStatus.SUCCEEDED, t.getStatus());
    }

    @Test
    void webhookReplayIsIdempotent() {
        Booking b = booking(BOOKING_ID, ServiceMode.REMOTE, "PENDING", 100.0);
        givenBooking(b);
        PaymentTransaction t = transaction(100L, BOOKING_ID, PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);
        when(transactions.findByCheckoutSessionId("cs_replay")).thenReturn(Optional.of(t));

        Session s = paid("cs_replay", 10000L, "usd", BOOKING_ID, "FULL", "pi_1", "100");
        service.applyCompletedCheckoutSession(s, null);
        service.applyCompletedCheckoutSession(s, null);

        assertEquals(PaymentTransactionStatus.SUCCEEDED, t.getStatus());
        assertEquals(100.0, b.getPaidAmount());
        verify(transactions, times(1)).save(t);
        verify(notifications, times(1)).createPaymentSuccessNotification(any(Booking.class), anyString(), anyString());
    }

    @Test
    void webhookThenConfirmFinalizesOnce() {
        webhookReplayIsIdempotent();
    }

    @Test
    void confirmThenWebhookFinalizesOnce() {
        webhookReplayIsIdempotent();
    }

    @Test
    void duplicateCaptureBeyondObligationIsQuarantined() {
        Booking b = booking(BOOKING_ID, ServiceMode.REMOTE, "PENDING", 100.0);
        givenBooking(b);

        PaymentTransaction first = transaction(100L, BOOKING_ID, PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);
        when(transactions.findByCheckoutSessionId("cs_first")).thenReturn(Optional.of(first));
        service.applyCompletedCheckoutSession(
                paid("cs_first", 10000L, "usd", BOOKING_ID, "FULL", "pi_1", "100"), null);
        assertEquals("PAID", b.getPaymentStatus());
        assertEquals(100.0, b.getPaidAmount());

        // A stale second session genuinely captures another 100 for the same obligation.
        PaymentTransaction second = transaction(101L, BOOKING_ID, PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);
        when(transactions.findByCheckoutSessionId("cs_second")).thenReturn(Optional.of(second));
        when(transactions.sumSuccessfulAmountMinorByBookingId(BOOKING_ID)).thenReturn(10000L);

        service.applyCompletedCheckoutSession(
                paid("cs_second", 10000L, "usd", BOOKING_ID, "FULL", "pi_2", "101"), null);

        assertEquals(PaymentTransactionStatus.SUCCEEDED, second.getStatus());
        assertTrue(second.isExcess(), "the second capture must be preserved but flagged excess");
        assertEquals(100.0, b.getPaidAmount(), "the booking aggregate must not become 200");
        assertEquals("PAID", b.getPaymentStatus());
    }

    // -------------------------------------------------------------------- expiry

    @Test
    void expiredSessionMarksTransactionExpired() {
        PaymentTransaction t = transaction(100L, BOOKING_ID, PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);
        when(transactions.findByCheckoutSessionId("cs_exp")).thenReturn(Optional.of(t));

        Session s = new Session();
        s.setId("cs_exp");
        service.applyExpiredCheckoutSession(s);

        assertEquals(PaymentTransactionStatus.EXPIRED, t.getStatus());
        verify(bookings, never()).save(any());
        verify(notifications, never()).createPaymentSuccessNotification(any(), anyString(), anyString());
    }

    @Test
    void duplicateExpiredEventIsIdempotent() {
        PaymentTransaction t = transaction(100L, BOOKING_ID, PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);
        when(transactions.findByCheckoutSessionId("cs_exp2")).thenReturn(Optional.of(t));
        Session s = new Session();
        s.setId("cs_exp2");

        service.applyExpiredCheckoutSession(s);
        service.applyExpiredCheckoutSession(s);

        assertEquals(PaymentTransactionStatus.EXPIRED, t.getStatus());
        verify(transactions, times(1)).save(t);
    }

    @Test
    void staleExpiredEventAfterSuccessDoesNotDowngrade() {
        PaymentTransaction t = transaction(100L, BOOKING_ID, PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.SUCCEEDED);
        when(transactions.findByCheckoutSessionId("cs_done")).thenReturn(Optional.of(t));
        Session s = new Session();
        s.setId("cs_done");

        service.applyExpiredCheckoutSession(s);

        assertEquals(PaymentTransactionStatus.SUCCEEDED, t.getStatus());
        verify(transactions, never()).save(any());
    }

    // ------------------------------------------------------------------ checkout

    @Test
    void zeroOutstandingBalanceCannotCreateCheckout() {
        Booking b = booking(BOOKING_ID, ServiceMode.REMOTE, "PAID", 100.0);
        givenBooking(b);
        when(transactions.sumSuccessfulAmountMinorByBookingId(BOOKING_ID)).thenReturn(10000L);

        assertThrows(RuntimeException.class,
                () -> service.createCheckoutSession(BOOKING_ID, "FULL", 7L, null));
        verifyNoInteractions(gateway);
    }

    @Test
    void remoteBookingRejectsNonFullPaymentType() {
        Booking b = booking(BOOKING_ID, ServiceMode.REMOTE, "PENDING", 100.0);
        givenBooking(b);

        assertThrows(RuntimeException.class,
                () -> service.createCheckoutSession(BOOKING_ID, "ADVANCE", 7L, null));
        verifyNoInteractions(gateway);
    }

    @Test
    void onsiteBookingRejectsFullPaymentType() {
        Booking b = booking(BOOKING_ID, ServiceMode.ONSITE, "PENDING", 100.0);
        givenBooking(b);

        assertThrows(RuntimeException.class,
                () -> service.createCheckoutSession(BOOKING_ID, "FULL", 7L, null));
        verifyNoInteractions(gateway);
    }

    @Test
    void duplicateActiveCheckoutIsReused() {
        Booking b = booking(BOOKING_ID, ServiceMode.REMOTE, "PENDING", 100.0);
        givenBooking(b);
        PaymentTransaction active = transaction(55L, BOOKING_ID, PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);
        active.setCheckoutSessionId("cs_active");
        active.setCheckoutUrl("https://pay/active");
        active.setCheckoutExpiresAt(LocalDateTime.now().plusHours(1));
        when(transactions.findFirstByBookingIdAndPaymentTypeAndStatusInAndExcessFalseOrderByCreatedAtDesc(
                anyLong(), any(), any())).thenReturn(Optional.of(active));

        StripeCheckoutResponse response = service.createCheckoutSession(BOOKING_ID, "FULL", 7L, null);

        assertEquals("https://pay/active", response.getCheckoutUrl());
        assertEquals("cs_active", response.getSessionId());
        verify(gateway, never()).createCheckoutSession(any());
        verify(transactions, never()).save(any());
    }

    @Test
    void expiredCheckoutIsRetiredAndNewAttemptCreatedWithoutOverwrite() {
        Booking b = booking(BOOKING_ID, ServiceMode.REMOTE, "PENDING", 100.0);
        givenBooking(b);
        PaymentTransaction active = transaction(55L, BOOKING_ID, PaymentType.FULL, 10000L, "USD",
                PaymentTransactionStatus.CHECKOUT_CREATED);
        active.setCheckoutSessionId("cs_old");
        active.setCheckoutUrl("https://pay/old");
        active.setCheckoutExpiresAt(LocalDateTime.now().minusMinutes(5));
        when(transactions.findFirstByBookingIdAndPaymentTypeAndStatusInAndExcessFalseOrderByCreatedAtDesc(
                anyLong(), any(), any())).thenReturn(Optional.of(active));

        StripeCheckoutResponse response = service.createCheckoutSession(BOOKING_ID, "FULL", 7L, null);

        assertEquals(PaymentTransactionStatus.EXPIRED, active.getStatus(), "the old attempt is retired, not overwritten");
        assertEquals("cs_old", active.getCheckoutSessionId());
        assertNotEquals("cs_old", response.getSessionId(), "a brand new Stripe session is created");
        assertTrue(savedTransactions.stream().anyMatch(t -> t.getStatus() == PaymentTransactionStatus.CHECKOUT_CREATED
                && !"cs_old".equals(t.getCheckoutSessionId())));
        verify(gateway, times(1)).createCheckoutSession(any());
    }

    @Test
    void newSuccessfulCheckoutCreatesOneLedgerRow() {
        Booking b = booking(BOOKING_ID, ServiceMode.REMOTE, "PENDING", 100.0);
        givenBooking(b);

        StripeCheckoutResponse response = service.createCheckoutSession(BOOKING_ID, "FULL", 7L, null);

        assertTrue(response.getSessionId().startsWith("cs_"));
        assertEquals(1, savedTransactions.size());
        assertEquals(PaymentTransactionStatus.CHECKOUT_CREATED, savedTransactions.get(0).getStatus());
        assertEquals(10000L, savedTransactions.get(0).getAmountMinor());
    }

    // ------------------------------------------------------------------- webhook

    @Test
    void invalidSignatureCausesNoMutation() {
        assertThrows(RuntimeException.class,
                () -> service.handleWebhook("{\"type\":\"customer.created\"}", "t=1,v1=deadbeef"));
        verify(transactions, never()).save(any());
        verify(paymentRefunds, never()).save(any());
    }

    @Test
    void unknownEventCausesNoMutation() throws Exception {
        String payload = "{\"id\":\"evt_unknown\",\"object\":\"event\",\"type\":\"customer.created\",\"data\":{\"object\":{}}}";
        service.handleWebhook(payload, sign(payload));
        verify(transactions, never()).save(any());
        verify(paymentRefunds, never()).save(any());
        verify(refundRequests, never()).save(any());
    }

    // -------------------------------------------------------------------- refund

    @Test
    void successfulRefundCannotBeDowngradedByStaleUpdate() {
        com.geekonsites.backend.entity.PaymentRefund execution = new com.geekonsites.backend.entity.PaymentRefund();
        execution.setId(1L);
        execution.setRefundRequestId(9L);
        execution.setAmountMinor(5000L);
        execution.setCurrency("USD");
        execution.setStatus(com.geekonsites.backend.enums.PaymentRefundStatus.SUCCEEDED);
        execution.setProviderRefundId("re_1");
        when(paymentRefunds.findByProviderRefundId("re_1")).thenReturn(Optional.of(execution));
        when(paymentRefunds.sumSucceededAmountMinorByRefundRequestId(9L)).thenReturn(5000L);
        RefundRequest request = new RefundRequest();
        request.setId(9L);
        request.setApprovedRefundAmount(java.math.BigDecimal.valueOf(50).setScale(2));
        when(refundRequests.findById(9L)).thenReturn(Optional.of(request));

        com.stripe.model.Refund stale = new com.stripe.model.Refund();
        stale.setId("re_1");
        stale.setStatus("failed");

        ReflectionTestUtils.invokeMethod(service, "applyRefundUpdate", stale);

        assertEquals(com.geekonsites.backend.enums.PaymentRefundStatus.SUCCEEDED, execution.getStatus());
        assertFalse(request.getRefundStatus() == RefundStatus.FAILED);
    }

    @Test
    void duplicateRefundUpdateDoesNotDoubleCount() {
        com.geekonsites.backend.entity.PaymentRefund execution = new com.geekonsites.backend.entity.PaymentRefund();
        execution.setId(1L);
        execution.setRefundRequestId(9L);
        execution.setAmountMinor(5000L);
        execution.setCurrency("USD");
        execution.setStatus(com.geekonsites.backend.enums.PaymentRefundStatus.PENDING);
        execution.setProviderRefundId("re_dup");
        when(paymentRefunds.findByProviderRefundId("re_dup")).thenReturn(Optional.of(execution));
        // Even after two updates, the remaining-successful total stays 5000, not 10000.
        when(paymentRefunds.sumSucceededAmountMinorByRefundRequestId(9L)).thenReturn(5000L);
        RefundRequest request = new RefundRequest();
        request.setId(9L);
        request.setApprovedRefundAmount(java.math.BigDecimal.valueOf(50).setScale(2));
        when(refundRequests.findById(9L)).thenReturn(Optional.of(request));

        com.stripe.model.Refund success = new com.stripe.model.Refund();
        success.setId("re_dup");
        success.setStatus("succeeded");

        ReflectionTestUtils.invokeMethod(service, "applyRefundUpdate", success);
        ReflectionTestUtils.invokeMethod(service, "applyRefundUpdate", success);

        assertEquals(RefundStatus.REFUNDED, request.getRefundStatus());
        verify(paymentRefunds, times(2)).findByProviderRefundId("re_dup");
    }
}
