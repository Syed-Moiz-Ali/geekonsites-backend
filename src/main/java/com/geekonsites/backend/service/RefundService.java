package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.AdminRefundDecisionDto;
import com.geekonsites.backend.dto.RefundRequestCreateDto;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.PaymentRefund;
import com.geekonsites.backend.entity.PaymentTransaction;
import com.geekonsites.backend.entity.RefundRequest;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.PaymentRefundStatus;
import com.geekonsites.backend.enums.PaymentTransactionStatus;
import com.geekonsites.backend.enums.RefundStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.PaymentRefundRepository;
import com.geekonsites.backend.repository.PaymentTransactionRepository;
import com.geekonsites.backend.repository.RefundRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * PHASE 1/9 — refunds executed against the payment ledger.
 *
 * <p>Business eligibility (whether a refund is allowed and its ceiling) is decided
 * exclusively by {@link RefundRuleEngine}. This service only changes HOW an approved
 * amount is executed.
 *
 * <p>PHASE 9 — the provider call no longer runs inside the refund transaction/lock:
 * <ol>
 *   <li><b>Reserve</b> (short transaction): lock the {@link RefundRequest}, validate,
 *       allocate across captured transactions and persist PENDING {@link PaymentRefund}
 *       rows with deterministic idempotency keys. Commit.</li>
 *   <li><b>Provider</b> (no transaction): call Stripe once per allocation using that
 *       allocation's stable idempotency key.</li>
 *   <li><b>Record</b> (short transaction per allocation): persist the provider refund id
 *       and status.</li>
 *   <li><b>Finalize</b> (short transaction): recompute and persist the request status.</li>
 * </ol>
 * The PENDING reservations still consume refundable capacity, so Phase 4's over-refund
 * protection is preserved.
 */
@Service
@Transactional
public class RefundService {
    private static final EnumSet<RefundStatus> ACTIVE = EnumSet.of(
            RefundStatus.REQUESTED, RefundStatus.UNDER_REVIEW, RefundStatus.APPROVED, RefundStatus.PROCESSING
    );
    private static final String IDEMPOTENCY_PREFIX = "gos-refund-";

    private final RefundRequestRepository refundRepository;
    private final BookingRepository bookingRepository;
    private final RefundRuleEngine ruleEngine;
    private final StripeRefundGateway stripeRefundGateway;
    private final EmailService emailService;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentRefundRepository paymentRefundRepository;
    private final PaymentRefundStateMachine paymentRefundStateMachine;
    private final TransactionTemplate transactionTemplate;

    public RefundService(RefundRequestRepository refundRepository, BookingRepository bookingRepository,
                         RefundRuleEngine ruleEngine, StripeRefundGateway stripeRefundGateway,
                         EmailService emailService, PaymentTransactionRepository paymentTransactionRepository,
                         PaymentRefundRepository paymentRefundRepository,
                         PaymentRefundStateMachine paymentRefundStateMachine,
                         PlatformTransactionManager transactionManager) {
        this.refundRepository = refundRepository;
        this.bookingRepository = bookingRepository;
        this.ruleEngine = ruleEngine;
        this.stripeRefundGateway = stripeRefundGateway;
        this.emailService = emailService;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.paymentRefundRepository = paymentRefundRepository;
        this.paymentRefundStateMachine = paymentRefundStateMachine;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public RefundRequest requestRefund(Long bookingId, RefundRequestCreateDto request, User customer) {
        requireCustomer(customer);
        Booking booking = booking(bookingId);
        if (!customer.getId().equals(booking.getCustomerId())) throw new RuntimeException("You cannot request a refund for another customer's booking");
        if (request.getReason() == null || request.getReason().isBlank()) throw new RuntimeException("Refund reason is required");
        if (refundRepository.existsByBookingIdAndRefundStatusIn(bookingId, ACTIVE)) throw new RuntimeException("An active refund request already exists for this booking");

        BigDecimal alreadyRefunded = refundedForBooking(bookingId);
        RefundRuleEngine.RefundAssessment assessment = ruleEngine.assess(booking, alreadyRefunded);
        if (assessment.maximumRefundableAmount().signum() <= 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "This booking has no refundable captured payment");
        }

        RefundRequest refund = new RefundRequest();
        refund.setBookingId(bookingId);
        refund.setCustomerId(customer.getId());
        refund.setCountry(normalizeCountry(booking.getCountry()));
        refund.setCurrency(booking.getCurrency());
        refund.setOriginalPaymentAmount(PaymentMoney.toMajorMoney(
                PaymentMoney.resolveMinor(booking.getPaidAmountMinor(), booking.getPaidAmount())));
        refund.setRequestedRefundAmount(assessment.maximumRefundableAmount());
        refund.setSuggestedMaximumRefundAmount(assessment.maximumRefundableAmount());
        refund.setRefundReason(request.getReason().trim());
        refund.setCustomerMessage(blankToNull(request.getMessage()));
        refund.setRefundStatus(RefundStatus.REQUESTED);
        refund.setRuleContext(assessment.ruleContext());
        refund.setRequestedAt(LocalDateTime.now());
        RefundRequest saved = refundRepository.save(refund);
        emailService.sendEmail(booking.getCustomerEmail(), "GeekOnSites refund request received",
                "We received your cancellation/refund request for booking GOS-" + bookingId + ". It will be reviewed before any refund is issued.");
        return saved;
    }

    @Transactional(readOnly = true)
    public List<RefundRequest> getCustomerRefunds(User customer) {
        requireCustomer(customer);
        return refundRepository.findByCustomerIdOrderByRequestedAtDesc(customer.getId());
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<RefundRequest> getCustomerRefunds(
            User customer, org.springframework.data.domain.Pageable pageable) {
        requireCustomer(customer);
        return refundRepository.findByCustomerIdOrderByRequestedAtDesc(customer.getId(), pageable);
    }

    @Transactional(readOnly = true)
    public RefundRequest getCustomerRefund(Long id, User customer) {
        requireCustomer(customer);
        RefundRequest refund = refund(id);
        if (!customer.getId().equals(refund.getCustomerId())) throw new RuntimeException("You cannot view another customer's refund");
        return refund;
    }

    @Transactional(readOnly = true)
    public List<RefundRequest> getAll() {
        return refundRepository.findAllByOrderByRequestedAtDesc();
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<RefundRequest> getAll(org.springframework.data.domain.Pageable pageable) {
        return refundRepository.findAll(pageable);
    }

    public RefundRequest review(Long id, User admin) {
        requireAdmin(admin);
        RefundRequest refund = refundRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Refund request not found"));
        if (refund.getRefundStatus() == RefundStatus.REQUESTED) {
            refund.setRefundStatus(RefundStatus.UNDER_REVIEW);
            refund.setReviewedAt(LocalDateTime.now());
            refund.setReviewedByAdminId(admin.getId());
            return refundRepository.save(refund);
        }
        return refund;
    }

    public RefundRequest reject(Long id, AdminRefundDecisionDto decision, User admin) {
        requireAdmin(admin);
        RefundRequest refund = refundRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Refund request not found"));
        ensureDecidable(refund);
        refund.setRefundStatus(RefundStatus.REJECTED);
        refund.setApprovedRefundAmount(BigDecimal.ZERO.setScale(2));
        refund.setAdminNote(blankToNull(decision.getAdminNote()));
        refund.setReviewedAt(LocalDateTime.now());
        refund.setReviewedByAdminId(admin.getId());
        RefundRequest saved = refundRepository.save(refund);
        Booking booking = booking(refund.getBookingId());
        emailService.sendEmail(booking.getCustomerEmail(), "GeekOnSites refund request update",
                "Your refund request for booking GOS-" + booking.getId() + " was reviewed. Please contact support if you need clarification.");
        return saved;
    }

    /**
     * PHASE 9 — two-phase execution. Deliberately NOT transactional: Phase 1 (reserve) and
     * Phase 3 (record) each open their own short transaction, so no DB lock is held while
     * Stripe is called.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RefundRequest approveAndExecute(Long id, AdminRefundDecisionDto decision, User admin) {
        requireAdmin(admin);

        ReservationPlan plan;
        try {
            plan = transactionTemplate.execute(status -> reserve(id, decision, admin));
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status -> markRequestFailed(id, exception));
            throw exception;
        }
        if (plan == null) {
            throw new RuntimeException("Refund request not found");
        }

        long succeededMinor = plan.baseSucceededMinor();
        try {
            for (ReservedAllocation reserved : plan.queue()) {
                StripeRefundGateway.StripeRefundResult result = stripeRefundGateway.refundPaymentTransaction(
                        reserved.transaction(), reserved.allocation().getAmountMinor(),
                        reserved.allocation().getIdempotencyKey());
                boolean succeeded = result.status() == null
                        || "succeeded".equalsIgnoreCase(result.status())
                        || "pending".equalsIgnoreCase(result.status());
                if (!succeeded) {
                    throw new RuntimeException("Stripe refund did not succeed for payment transaction "
                            + reserved.transaction().getId());
                }
                succeededMinor += reserved.allocation().getAmountMinor();
                transactionTemplate.executeWithoutResult(status -> recordSuccess(reserved.allocation(), result));
            }
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status -> markRequestFailed(id, exception));
            throw exception;
        }

        long finalSucceededMinor = succeededMinor;
        return transactionTemplate.execute(status -> finalizeRequest(id, finalSucceededMinor));
    }

    // ------------------------------------------------------------------ Phase 1: reserve

    private ReservationPlan reserve(Long id, AdminRefundDecisionDto decision, User admin) {
        RefundRequest refund = refundRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Refund request not found"));
        ensureDecidable(refund);
        Booking booking = booking(refund.getBookingId());
        BigDecimal requested = decision.getAmount() == null
                ? refund.getSuggestedMaximumRefundAmount()
                : decision.getAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal currentMaximum = ruleEngine.assess(booking, refundedForBooking(booking.getId())).maximumRefundableAmount()
                .min(refund.getSuggestedMaximumRefundAmount());
        if (requested.signum() <= 0 || requested.compareTo(currentMaximum) > 0) {
            throw new RuntimeException("Approved refund exceeds the backend-validated refundable amount");
        }

        refund.setApprovedRefundAmount(requested);
        refund.setAdminNote(blankToNull(decision.getAdminNote()));
        refund.setReviewedAt(LocalDateTime.now());
        refund.setReviewedByAdminId(admin.getId());
        refund.setRefundStatus(RefundStatus.PROCESSING);
        refund.setFailureReason(null);
        refundRepository.save(refund);

        long requestedMinor = PaymentMoney.toMinor(requested);
        long baseSucceededMinor = paymentRefundRepository.sumSucceededAmountMinorByRefundRequestId(refund.getId());
        long remainingMinor = Math.max(0L, requestedMinor - baseSucceededMinor);

        List<PaymentTransaction> captured = new ArrayList<>(
                paymentTransactionRepository.findByBookingIdAndStatusOrderByCreatedAtAsc(
                        booking.getId(), PaymentTransactionStatus.SUCCEEDED));
        // Deterministic technical allocation: most recent successful capture first.
        Collections.reverse(captured);

        List<ReservedAllocation> queue = new ArrayList<>();
        for (PaymentTransaction transaction : captured) {
            if (remainingMinor <= 0) break;

            PaymentRefund existing = paymentRefundRepository
                    .findByRefundRequestIdAndPaymentTransactionId(refund.getId(), transaction.getId())
                    .orElse(null);
            if (existing != null && existing.getStatus() == PaymentRefundStatus.SUCCEEDED) {
                continue;
            }
            if (existing != null && existing.getStatus() == PaymentRefundStatus.PENDING) {
                // Already reserved by an earlier attempt; re-queue for provider processing and
                // count its amount against the outstanding requested total (otherwise a retry
                // after a provider failure would be unable to re-drive the refund).
                remainingMinor -= Math.min(existing.getAmountMinor(), remainingMinor);
                queue.add(new ReservedAllocation(existing, transaction));
                continue;
            }

            // PENDING/SUCCEEDED reservations consume capacity; a FAILED allocation frees it.
            long capacity = transaction.getAmountMinor()
                    - paymentRefundRepository.sumActiveAmountMinorByPaymentTransactionId(transaction.getId());
            if (capacity <= 0) continue;

            long slice = Math.min(capacity, remainingMinor);
            PaymentRefund allocation = existing != null ? existing : newAllocation(refund.getId(), transaction);
            allocation.setAmountMinor(slice);
            allocation.setCurrency(transaction.getCurrency());
            allocation.setStatus(PaymentRefundStatus.PENDING);
            paymentRefundRepository.save(allocation);
            queue.add(new ReservedAllocation(allocation, transaction));
            remainingMinor -= slice;
        }

        if (remainingMinor > 0) {
            throw new RuntimeException("Refund could not be fully allocated across captured payments");
        }
        return new ReservationPlan(queue, baseSucceededMinor);
    }

    // -------------------------------------------------------------- Phase 3: record

    private void recordSuccess(PaymentRefund allocation, StripeRefundGateway.StripeRefundResult result) {
        PaymentRefund managed = allocation.getId() == null
                ? allocation
                : paymentRefundRepository.findByIdForUpdate(allocation.getId()).orElse(allocation);
        if (managed.getStatus() == PaymentRefundStatus.SUCCEEDED) {
            return;
        }
        managed.setPaymentIntentId(result.paymentIntentId());
        paymentRefundStateMachine.markSucceeded(managed, result.refundId());
        paymentRefundRepository.save(managed);
    }

    // ------------------------------------------------------------- Phase 4: finalize

    private RefundRequest finalizeRequest(Long id, long succeededMinor) {
        RefundRequest refund = refundRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Refund request not found"));
        Booking booking = booking(refund.getBookingId());
        List<PaymentRefund> allocations = paymentRefundRepository.findByRefundRequestIdOrderByCreatedAtAsc(id);
        String firstIntentId = refund.getStripePaymentIntentId();
        String lastRefundId = refund.getStripeRefundId();
        for (PaymentRefund allocation : allocations) {
            if (firstIntentId == null && allocation.getPaymentIntentId() != null) {
                firstIntentId = allocation.getPaymentIntentId();
            }
            if (allocation.getProviderRefundId() != null) {
                lastRefundId = allocation.getProviderRefundId();
            }
        }
        refund.setStripePaymentIntentId(firstIntentId);
        refund.setStripeRefundId(lastRefundId);
        refund.setProcessedAt(LocalDateTime.now());
        long capturedMinor = PaymentMoney.resolveMinor(booking.getPaidAmountMinor(), booking.getPaidAmount());
        refund.setRefundStatus(succeededMinor < capturedMinor
                ? RefundStatus.PARTIALLY_REFUNDED : RefundStatus.REFUNDED);
        RefundRequest saved = refundRepository.save(refund);
        emailService.sendEmail(booking.getCustomerEmail(), "GeekOnSites refund processed",
                "A " + booking.getCurrency() + " " + refund.getApprovedRefundAmount()
                        + " refund was processed for booking GOS-" + booking.getId()
                        + ". Your bank may require additional processing time.");
        return saved;
    }

    private void markRequestFailed(Long id, RuntimeException exception) {
        refundRepository.findById(id).ifPresent(refund -> {
            refund.setRefundStatus(RefundStatus.FAILED);
            refund.setFailureReason(safeFailure(exception));
            refund.setProcessedAt(LocalDateTime.now());
            refundRepository.save(refund);
        });
    }

    private PaymentRefund newAllocation(Long refundRequestId, PaymentTransaction transaction) {
        PaymentRefund allocation = new PaymentRefund();
        allocation.setRefundRequestId(refundRequestId);
        allocation.setPaymentTransactionId(transaction.getId());
        allocation.setCurrency(transaction.getCurrency());
        allocation.setStatus(PaymentRefundStatus.PENDING);
        allocation.setIdempotencyKey(IDEMPOTENCY_PREFIX + refundRequestId + "-" + transaction.getId());
        return allocation;
    }

    private BigDecimal refundedForBooking(Long bookingId) {
        return refundRepository.findAllByOrderByRequestedAtDesc().stream()
                .filter(item -> bookingId.equals(item.getBookingId()))
                .filter(item -> item.getRefundStatus() == RefundStatus.REFUNDED || item.getRefundStatus() == RefundStatus.PARTIALLY_REFUNDED)
                .map(RefundRequest::getApprovedRefundAmount).filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void ensureDecidable(RefundRequest refund) {
        if (refund.getRefundStatus() != RefundStatus.REQUESTED && refund.getRefundStatus() != RefundStatus.UNDER_REVIEW
                && refund.getRefundStatus() != RefundStatus.FAILED) throw new RuntimeException("Refund request can no longer be changed");
    }
    private Booking booking(Long id) { return bookingRepository.findById(id).orElseThrow(() -> new RuntimeException("Booking not found")); }
    private RefundRequest refund(Long id) {
        return refundRepository.findById(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Refund request not found"));
    }
    private void requireCustomer(User user) { if (user == null || user.getRole() != Role.CUSTOMER) throw new RuntimeException("Customer authorization required"); }
    private void requireAdmin(User user) { if (user == null || user.getRole() != Role.ADMIN) throw new RuntimeException("Admin authorization required"); }
    private String normalizeCountry(String value) { String v = value == null ? "US" : value.trim().toUpperCase(Locale.ROOT); return v.equals("GB") || v.contains("UNITED") ? "UK" : v; }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String safeFailure(RuntimeException exception) { String value = exception.getMessage(); return value == null ? "Stripe refund failed" : value.substring(0, Math.min(value.length(), 500)); }

    private record ReservedAllocation(PaymentRefund allocation, PaymentTransaction transaction) {}
    private record ReservationPlan(List<ReservedAllocation> queue, long baseSucceededMinor) {}
}
