package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.StripeCheckoutResponse;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.exception.ResourceNotFoundException;
import com.geekonsites.backend.entity.PaymentRefund;
import com.geekonsites.backend.entity.PaymentTransaction;
import com.geekonsites.backend.enums.PaymentProvider;
import com.geekonsites.backend.enums.PaymentReversalStatus;
import com.geekonsites.backend.enums.PaymentTransactionStatus;
import com.geekonsites.backend.enums.PaymentType;
import com.geekonsites.backend.enums.RefundStatus;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.PaymentRefundRepository;
import com.geekonsites.backend.repository.PaymentTransactionRepository;
import com.geekonsites.backend.repository.RefundRequestRepository;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PHASE 1/3/4 — payment orchestration backed by the {@link PaymentTransaction} ledger.
 *
 * <p>PHASE 4: payment finalization is a short {@code @Transactional} critical section that
 * locks the {@code Booking} row first (global lock order: Booking → PaymentTransaction →
 * PaymentRefund → RefundRequest → Technician). External side effects (remote provisioning,
 * excess reversal) are dispatched AFTER commit via application events. Checkout creation is
 * two-phase so no DB lock is held during the Stripe network call.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final List<PaymentTransactionStatus> ACTIVE_CHECKOUT_STATES =
            List.of(PaymentTransactionStatus.INITIATED, PaymentTransactionStatus.CHECKOUT_CREATED);

    private final BookingRepository bookingRepository;
    private final InvoiceService invoiceService;
    private final RemoteSessionProvisioningService remoteSessionProvisioningService;
    private final UkEarlyServiceConsentService ukEarlyServiceConsentService;
    private final RefundRequestRepository refundRequestRepository;
    private final NotificationService notificationService;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentRefundRepository paymentRefundRepository;
    private final BookingStateMachine bookingStateMachine;
    private final PaymentTransactionStateMachine paymentTransactionStateMachine;
    private final PaymentRefundStateMachine paymentRefundStateMachine;
    private final StripeCheckoutGateway stripeCheckoutGateway;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    @Value("${stripe.secret.key}")
    private String stripeSecretKey;

    @Value("${stripe.success.url}")
    private String successUrl;

    @Value("${stripe.cancel.url}")
    private String cancelUrl;

    @Value("${stripe.webhook.secret}")
    private String webhookSecret;

    public PaymentService(
            BookingRepository bookingRepository,
            InvoiceService invoiceService,
            RemoteSessionProvisioningService remoteSessionProvisioningService,
            UkEarlyServiceConsentService ukEarlyServiceConsentService,
            RefundRequestRepository refundRequestRepository,
            NotificationService notificationService,
            PaymentTransactionRepository paymentTransactionRepository,
            PaymentRefundRepository paymentRefundRepository,
            BookingStateMachine bookingStateMachine,
            PaymentTransactionStateMachine paymentTransactionStateMachine,
            PaymentRefundStateMachine paymentRefundStateMachine,
            StripeCheckoutGateway stripeCheckoutGateway,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager
    ) {
        this.bookingRepository = bookingRepository;
        this.invoiceService = invoiceService;
        this.remoteSessionProvisioningService = remoteSessionProvisioningService;
        this.ukEarlyServiceConsentService = ukEarlyServiceConsentService;
        this.refundRequestRepository = refundRequestRepository;
        this.notificationService = notificationService;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.paymentRefundRepository = paymentRefundRepository;
        this.bookingStateMachine = bookingStateMachine;
        this.paymentTransactionStateMachine = paymentTransactionStateMachine;
        this.paymentRefundStateMachine = paymentRefundStateMachine;
        this.stripeCheckoutGateway = stripeCheckoutGateway;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ================================================================ checkout

    public StripeCheckoutResponse createCheckoutSession(
            Long bookingId,
            String paymentType,
            Long customerId,
            Boolean ukEarlyServiceConsent
    ) {
        Booking existing = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (!customerId.equals(existing.getCustomerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot pay for another customer's booking");
        }
        String normalizedPaymentType = normalizePaymentType(paymentType);

        // Phase 1 — short transaction: lock booking, validate, reserve/reuse an attempt.
        CheckoutAttempt attempt = transactionTemplate.execute(status ->
                reserveCheckoutAttempt(bookingId, customerId, normalizedPaymentType, ukEarlyServiceConsent));

        if (attempt.reusable()) {
            return new StripeCheckoutResponse(attempt.checkoutUrl(), attempt.checkoutSessionId());
        }

        // Phase 2 — provider call with NO DB lock held.
        StripeCheckoutGateway.CheckoutSession session;
        try {
            session = stripeCheckoutGateway.createCheckoutSession(new StripeCheckoutGateway.CheckoutRequest(
                    attempt.transactionId(),
                    bookingId,
                    "GeekOnSites Booking GOS-" + bookingId,
                    existing.getServiceType(),
                    attempt.amountMinor(),
                    attempt.currency(),
                    successUrl + "?bookingId=" + bookingId + "&session_id={CHECKOUT_SESSION_ID}",
                    cancelUrl + "?bookingId=" + bookingId,
                    normalizedPaymentType
            ));
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status -> markAttemptFailed(attempt.transactionId()));
            throw exception;
        }

        // Phase 3 — short transaction: persist the provider session.
        transactionTemplate.executeWithoutResult(status ->
                markAttemptCreated(attempt.transactionId(), session));
        return new StripeCheckoutResponse(session.url(), session.sessionId());
    }

    private CheckoutAttempt reserveCheckoutAttempt(
            Long bookingId, Long customerId, String paymentType, Boolean ukEarlyServiceConsent
    ) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (!customerId.equals(booking.getCustomerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot pay for another customer's booking");
        }
        validateCheckoutAllowed(booking, paymentType);
        ukEarlyServiceConsentService.recordForInitialPayment(booking, customerId, paymentType, ukEarlyServiceConsent);

        long amountMinor = expectedPaymentAmountMinor(booking, paymentType);
        if (amountMinor <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment amount");
        }
        String currency = PaymentMoney.normalizeCurrency(booking.getCurrency());
        PaymentType type = PaymentType.valueOf(paymentType);

        PaymentTransaction transaction = paymentTransactionRepository
                .findFirstByBookingIdAndPaymentTypeAndStatusInAndExcessFalseOrderByCreatedAtDesc(
                        bookingId, type, ACTIVE_CHECKOUT_STATES)
                .orElse(null);

        if (transaction != null) {
            boolean matches = PaymentMoney.sameCurrency(transaction.getCurrency(), currency)
                    && transaction.getAmountMinor() != null
                    && transaction.getAmountMinor() == amountMinor;
            if (!matches) {
                paymentTransactionStateMachine.markExpired(transaction);
                paymentTransactionRepository.save(transaction);
                transaction = null;
            } else if (transaction.getStatus() == PaymentTransactionStatus.CHECKOUT_CREATED
                    && isUsableCheckout(transaction)) {
                return CheckoutAttempt.reuse(transaction.getCheckoutUrl(), transaction.getCheckoutSessionId());
            } else if (transaction.getStatus() == PaymentTransactionStatus.CHECKOUT_CREATED) {
                paymentTransactionStateMachine.markExpired(transaction);
                paymentTransactionRepository.save(transaction);
                transaction = null;
            }
        }

        if (transaction == null) {
            transaction = new PaymentTransaction();
            transaction.setBookingId(bookingId);
            transaction.setCustomerId(customerId);
            transaction.setPaymentType(type);
            transaction.setProvider(PaymentProvider.STRIPE);
            transaction.setAmountMinor(amountMinor);
            transaction.setCurrency(currency);
            transaction.setStatus(PaymentTransactionStatus.INITIATED);
            transaction = paymentTransactionRepository.save(transaction);
        }
        return CheckoutAttempt.create(transaction.getId(), amountMinor, currency);
    }

    private void markAttemptCreated(Long transactionId, StripeCheckoutGateway.CheckoutSession session) {
        PaymentTransaction transaction = paymentTransactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new RuntimeException("Payment transaction not found"));
        if (session.sessionId().equals(transaction.getCheckoutSessionId())) {
            return;
        }
        paymentTransactionStateMachine.markCheckoutCreated(
                transaction, session.sessionId(), session.url(), session.expiresAt());
        paymentTransactionRepository.save(transaction);
    }

    private void markAttemptFailed(Long transactionId) {
        paymentTransactionRepository.findByIdForUpdate(transactionId).ifPresent(transaction -> {
            paymentTransactionStateMachine.markFailed(transaction);
            paymentTransactionRepository.save(transaction);
        });
    }

    // ================================================================ webhook

    public void handleWebhook(String payload, String sigHeader) {
        try {
            Event event = Webhook.constructEvent(payload, sigHeader, webhookSecret);

            switch (event.getType()) {
                case "checkout.session.completed" -> {
                    Session session = (Session) event.getDataObjectDeserializer()
                            .getObject()
                            .orElseThrow(() -> new RuntimeException("Unable to deserialize Stripe session"));
                    if (isPaid(session)) {
                        applyCompletedCheckoutSession(session, null);
                    }
                }
                case "checkout.session.expired" -> {
                    Session session = (Session) event.getDataObjectDeserializer()
                            .getObject()
                            .orElseThrow(() -> new RuntimeException("Unable to deserialize Stripe session"));
                    applyExpiredCheckoutSession(session);
                }
                case "refund.updated" -> {
                    Refund stripeRefund = (Refund) event.getDataObjectDeserializer()
                            .getObject()
                            .orElseThrow(() -> new RuntimeException("Unable to deserialize Stripe refund"));
                    applyRefundUpdate(stripeRefund);
                }
                default -> {
                    // Unsupported event types are acknowledged and ignored: no mutation.
                }
            }

        } catch (SignatureVerificationException e) {
            // Provider-facing: an invalid signature is a client error (400), not a server defect.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Stripe webhook signature");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Stripe webhook failed: " + e.getMessage());
        }
    }

    public Booking confirmCheckoutSession(String sessionId, Long customerId) {
        try {
            Stripe.apiKey = stripeSecretKey;
            Session session = Session.retrieve(sessionId);

            if (!isPaid(session)) {
                throw new RuntimeException("Stripe has not confirmed this payment yet");
            }

            return applyCompletedCheckoutSession(session, customerId);
        } catch (Exception e) {
            throw new RuntimeException("Unable to confirm Stripe payment: " + e.getMessage());
        }
    }

    /**
     * Shared finalization path (webhook + confirmation). Short locked critical section:
     * lock Booking first, then re-check and apply. No Stripe call is made while locked.
     */
    @Transactional
    public Booking applyCompletedCheckoutSession(Session session, Long expectedCustomerId) {
        String bookingIdText = session.getMetadata().get("bookingId");
        String paymentTypeText = session.getMetadata().get("paymentType");
        String transactionIdText = session.getMetadata().get("paymentTransactionId");

        if (bookingIdText == null) {
            throw new RuntimeException("Booking ID missing in Stripe metadata");
        }

        Long bookingId = Long.parseLong(bookingIdText);
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        if (expectedCustomerId != null && !expectedCustomerId.equals(booking.getCustomerId())) {
            throw new RuntimeException("You cannot confirm another customer's payment");
        }

        if (!isPaid(session)) {
            throw new RuntimeException("Stripe has not confirmed this payment");
        }

        String normalizedPaymentType = normalizePaymentType(paymentTypeText);

        PaymentTransaction transaction = resolveTransaction(session, booking, normalizedPaymentType, transactionIdText);

        if (!bookingId.equals(transaction.getBookingId())) {
            throw new RuntimeException("Stripe payment does not belong to this booking");
        }
        if (transaction.getPaymentType() != PaymentType.valueOf(normalizedPaymentType)) {
            throw new RuntimeException("Stripe payment type does not match the payment transaction");
        }

        // Ledger-authoritative idempotency (webhook replay / repeated confirm).
        if (transaction.getStatus() == PaymentTransactionStatus.SUCCEEDED) {
            return bookingRepository.findById(bookingId).orElse(booking);
        }

        // Legacy bookings finalized before the ledger existed have no SUCCEEDED row.
        if (isLegacyAlreadyPaid(booking, session)) {
            notificationService.createPaymentSuccessNotification(booking, normalizedPaymentType, session.getId());
            return finalizeLocalBooking(booking);
        }

        verifyStripeAmountAndCurrency(session, transaction);

        long obligationMinor = PaymentMoney.resolveMinor(booking.getTotalAmountMinor(), booking.getTotalAmount());
        long alreadyPaidMinor = paymentTransactionRepository.sumSuccessfulAmountMinorByBookingId(bookingId);
        long incomingMinor = transaction.getAmountMinor() == null ? 0L : transaction.getAmountMinor();
        boolean excess = obligationMinor > 0 && (alreadyPaidMinor + incomingMinor > obligationMinor);

        if (excess) {
            paymentTransactionStateMachine.markSucceeded(transaction, session.getPaymentIntent());
            paymentTransactionStateMachine.markExcess(transaction);
            transaction.setReversalStatus(PaymentReversalStatus.PENDING);
            paymentTransactionRepository.save(transaction);
            log.warn("Quarantined excess capture transaction={} booking={} alreadyPaidMinor={} incomingMinor={} obligationMinor={}",
                    transaction.getId(), bookingId, alreadyPaidMinor, incomingMinor, obligationMinor);
            // AFTER COMMIT: idempotent technical reversal of the excess capture.
            eventPublisher.publishEvent(new ExcessPaymentReversalRequestedEvent(transaction.getId()));
            return booking;
        }

        validatePaymentStage(booking, normalizedPaymentType);
        paymentTransactionStateMachine.markSucceeded(transaction, session.getPaymentIntent());
        transaction.setCheckoutSessionId(session.getId());
        paymentTransactionRepository.save(transaction);

        applyBookingPaymentAggregate(booking, normalizedPaymentType, session.getId());
        Booking savedBooking = bookingRepository.save(booking);
        notificationService.createPaymentSuccessNotification(savedBooking, normalizedPaymentType, session.getId());
        Booking result = finalizeLocalBooking(savedBooking);
        // AFTER COMMIT: Google Calendar / Meet provisioning (remote only, no DB lock held).
        if (isRemoteService(booking)) {
            eventPublisher.publishEvent(new RemoteSessionProvisionRequestedEvent(bookingId));
        }
        return result;
    }

    @Transactional
    public void applyExpiredCheckoutSession(Session session) {
        if (session == null || session.getId() == null) {
            return;
        }
        paymentTransactionRepository.findByCheckoutSessionId(session.getId()).ifPresent(transaction -> {
            if (paymentTransactionStateMachine.markExpired(transaction)) {
                paymentTransactionRepository.save(transaction);
            }
        });
    }

    private boolean isPaid(Session session) {
        return session.getPaymentStatus() != null && "paid".equalsIgnoreCase(session.getPaymentStatus());
    }

    private boolean isUsableCheckout(PaymentTransaction transaction) {
        if (transaction.getCheckoutUrl() == null || transaction.getCheckoutSessionId() == null) {
            return false;
        }
        return transaction.getCheckoutExpiresAt() == null
                || transaction.getCheckoutExpiresAt().isAfter(LocalDateTime.now());
    }

    private boolean isLegacyAlreadyPaid(Booking booking, Session session) {
        return session.getId().equals(booking.getPaymentTransactionId())
                && "PAID".equalsIgnoreCase(booking.getPaymentStatus());
    }

    private PaymentTransaction resolveTransaction(
            Session session, Booking booking, String normalizedPaymentType, String transactionIdText
    ) {
        PaymentTransaction transaction = paymentTransactionRepository
                .findByCheckoutSessionId(session.getId())
                .orElse(null);

        if (transaction == null && transactionIdText != null) {
            try {
                transaction = paymentTransactionRepository.findById(Long.parseLong(transactionIdText)).orElse(null);
            } catch (NumberFormatException ignored) {
                transaction = null;
            }
        }

        // LEGACY FALLBACK (transitional compatibility, isolated here): a booking created
        // before the ledger existed, or a row lost between provider creation and
        // persistence. Persisted only after Stripe reports the payment paid AND the
        // amount/currency are verified against the server-computed booking.
        if (transaction == null) {
            transaction = recoverLegacyTransaction(booking, normalizedPaymentType, session.getId());
        }
        return transaction;
    }

    private PaymentTransaction recoverLegacyTransaction(Booking booking, String normalizedPaymentType, String sessionId) {
        log.warn("Recovering legacy (pre-ledger) payment transaction for booking {} session {}", booking.getId(), sessionId);
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setBookingId(booking.getId());
        transaction.setCustomerId(booking.getCustomerId());
        transaction.setPaymentType(PaymentType.valueOf(normalizedPaymentType));
        transaction.setProvider(PaymentProvider.STRIPE);
        transaction.setAmountMinor(expectedPaymentAmountMinor(booking, normalizedPaymentType));
        transaction.setCurrency(PaymentMoney.normalizeCurrency(booking.getCurrency()));
        transaction.setCheckoutSessionId(sessionId);
        transaction.setStatus(PaymentTransactionStatus.INITIATED);
        return transaction;
    }

    private void applyBookingPaymentAggregate(Booking booking, String normalizedPaymentType, String sessionId) {
        booking.setPaymentTransactionId(sessionId);
        booking.setPaymentMethod("STRIPE");
        booking.setPaymentType(normalizedPaymentType);

        long ledgerPaidMinor = paymentTransactionRepository.sumSuccessfulAmountMinorByBookingId(booking.getId());
        long totalMinor = PaymentMoney.resolveMinor(booking.getTotalAmountMinor(), booking.getTotalAmount());
        long advanceMinor = PaymentMoney.resolveMinor(booking.getAdvanceAmountMinor(), booking.getAdvanceAmount());
        long paidMinor;
        long remainingMinor;

        if ("ADVANCE".equals(normalizedPaymentType)) {
            paidMinor = ledgerPaidMinor > 0 ? ledgerPaidMinor : advanceMinor;
            remainingMinor = Math.max(totalMinor - paidMinor, 0);
            booking.setPaymentStatus("PARTIALLY_PAID");
            bookingStateMachine.onPaymentConfirmed(booking, PaymentType.ADVANCE);
        } else if ("REMAINING".equals(normalizedPaymentType)) {
            paidMinor = ledgerPaidMinor > 0 ? ledgerPaidMinor : totalMinor;
            remainingMinor = 0;
            booking.setPaymentStatus("PAID");
            bookingStateMachine.onPaymentConfirmed(booking, PaymentType.REMAINING);
        } else {
            paidMinor = ledgerPaidMinor > 0 ? ledgerPaidMinor : totalMinor;
            remainingMinor = 0;
            booking.setPaymentStatus("PAID");
            bookingStateMachine.onPaymentConfirmed(booking, PaymentType.FULL);
        }

        // PHASE 8 — exact minor units are authoritative; Double fields are mirrors.
        booking.setPaidAmountMinor(paidMinor);
        booking.setPaidAmount(PaymentMoney.toMajor(paidMinor));
        booking.setRemainingAmountMinor(remainingMinor);
        booking.setRemainingAmount(PaymentMoney.toMajor(remainingMinor));
    }

    @Transactional
    public void applyRefundUpdate(Refund stripeRefund) {
        paymentRefundRepository.findByProviderRefundId(stripeRefund.getId()).ifPresent(execution -> {
            if ("succeeded".equalsIgnoreCase(stripeRefund.getStatus())) {
                paymentRefundStateMachine.markSucceeded(execution, stripeRefund.getId());
            } else if ("failed".equalsIgnoreCase(stripeRefund.getStatus())
                    || "canceled".equalsIgnoreCase(stripeRefund.getStatus())) {
                paymentRefundStateMachine.markFailed(execution);
            }
            paymentRefundRepository.save(execution);
            recomputeRefundRequest(execution.getRefundRequestId());
        });
    }

    private void recomputeRefundRequest(Long refundRequestId) {
        refundRequestRepository.findById(refundRequestId).ifPresent(request -> {
            long succeededMinor = paymentRefundRepository.sumSucceededAmountMinorByRefundRequestId(request.getId());
            if (succeededMinor > 0) {
                long approvedMinor = PaymentMoney.toMinor(request.getApprovedRefundAmount());
                request.setRefundStatus(succeededMinor >= approvedMinor
                        ? RefundStatus.REFUNDED : RefundStatus.PARTIALLY_REFUNDED);
                request.setProcessedAt(LocalDateTime.now());
                refundRequestRepository.save(request);
            }
        });
    }

    private String normalizePaymentType(String paymentType) {
        String value = paymentType == null ? "FULL" : paymentType.trim().toUpperCase();
        if (!value.equals("FULL") && !value.equals("ADVANCE") && !value.equals("REMAINING")) {
            throw new RuntimeException("Unsupported payment type");
        }
        return value;
    }

    private void validateCheckoutAllowed(Booking booking, String paymentType) {
        boolean onsite = booking.getServiceMode() == ServiceMode.ONSITE;
        long obligationMinor = PaymentMoney.resolveMinor(booking.getTotalAmountMinor(), booking.getTotalAmount());
        long alreadyPaidMinor = paymentTransactionRepository.sumSuccessfulAmountMinorByBookingId(booking.getId());
        String status = booking.getPaymentStatus() == null ? "" : booking.getPaymentStatus();

        if (obligationMinor > 0 && obligationMinor - alreadyPaidMinor <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking has no outstanding balance to pay");
        }

        if (onsite) {
            if ("FULL".equals(paymentType)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "On-site services use advance payment before the visit");
            }
            if ("ADVANCE".equals(paymentType)
                    && (alreadyPaidMinor > 0 || "PARTIALLY_PAID".equalsIgnoreCase(status) || "PAID".equalsIgnoreCase(status))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The advance payment has already been processed");
            }
            if ("REMAINING".equals(paymentType) && !"BALANCE_PENDING".equalsIgnoreCase(status)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The remaining balance is not due yet");
            }
        } else {
            if (!"FULL".equals(paymentType)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Remote services require full payment");
            }
            if (alreadyPaidMinor > 0 || "PAID".equalsIgnoreCase(status)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking has already been paid");
            }
        }
    }

    private void validatePaymentStage(Booking booking, String paymentType) {
        boolean onsite = booking.getServiceMode() != null && "ONSITE".equals(booking.getServiceMode().name());
        if (!onsite && !"FULL".equals(paymentType)) {
            throw new RuntimeException("Remote services require full payment");
        }
        if (onsite && "FULL".equals(paymentType)) {
            throw new RuntimeException("On-site services use advance payment before the visit");
        }
        if ("ADVANCE".equals(paymentType) && !"PENDING".equalsIgnoreCase(booking.getPaymentStatus())) {
            throw new RuntimeException("The advance payment has already been processed");
        }
        if ("REMAINING".equals(paymentType)
                && !"BALANCE_PENDING".equalsIgnoreCase(booking.getPaymentStatus())) {
            throw new RuntimeException("The remaining balance is not due yet");
        }
        if ("FULL".equals(paymentType) && !"PENDING".equalsIgnoreCase(booking.getPaymentStatus())) {
            throw new RuntimeException("This booking has already been paid");
        }
    }

    private long expectedPaymentAmountMinor(Booking booking, String paymentType) {
        if ("ADVANCE".equals(paymentType)) {
            return PaymentMoney.resolveMinor(booking.getAdvanceAmountMinor(), booking.getAdvanceAmount());
        }
        if ("REMAINING".equals(paymentType)) {
            return PaymentMoney.resolveMinor(booking.getRemainingAmountMinor(), booking.getRemainingAmount());
        }
        return PaymentMoney.resolveMinor(booking.getTotalAmountMinor(), booking.getTotalAmount());
    }

    private void verifyStripeAmountAndCurrency(Session session, PaymentTransaction transaction) {
        if (session.getAmountTotal() == null || !session.getAmountTotal().equals(transaction.getAmountMinor())) {
            throw new RuntimeException("Stripe payment amount does not match the booking");
        }
        if (!PaymentMoney.sameCurrency(transaction.getCurrency(), session.getCurrency())) {
            throw new RuntimeException("Stripe payment currency does not match the booking");
        }
    }

    /** Local-only finalization (invoice). External provisioning is dispatched after commit. */
    private Booking finalizeLocalBooking(Booking booking) {
        if ("PARTIALLY_PAID".equalsIgnoreCase(booking.getPaymentStatus())) {
            invoiceService.generateInvoiceFromBooking(booking.getId());
            return bookingRepository.findById(booking.getId()).orElse(booking);
        }
        if (!"PAID".equalsIgnoreCase(booking.getPaymentStatus())) return booking;
        invoiceService.generateInvoiceFromBooking(booking.getId());
        return bookingRepository.findById(booking.getId()).orElse(booking);
    }

    private boolean isRemoteService(Booking booking) {
        return booking.getServiceMode() == ServiceMode.REMOTE
                || Boolean.TRUE.equals(booking.getRemoteSessionRequired());
    }

    private record CheckoutAttempt(
            boolean reusable, Long transactionId, String checkoutUrl, String checkoutSessionId,
            long amountMinor, String currency
    ) {
        static CheckoutAttempt reuse(String url, String sessionId) {
            return new CheckoutAttempt(true, null, url, sessionId, 0L, null);
        }
        static CheckoutAttempt create(Long transactionId, long amountMinor, String currency) {
            return new CheckoutAttempt(false, transactionId, null, null, amountMinor, currency);
        }
    }
}
