package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.AdminRefundDecisionDto;
import com.geekonsites.backend.dto.RefundRequestCreateDto;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.RefundRequest;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.RefundStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.RefundRequestRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

@Service
public class RefundService {
    private static final EnumSet<RefundStatus> ACTIVE = EnumSet.of(
            RefundStatus.REQUESTED, RefundStatus.UNDER_REVIEW, RefundStatus.APPROVED, RefundStatus.PROCESSING
    );

    private final RefundRequestRepository refundRepository;
    private final BookingRepository bookingRepository;
    private final RefundRuleEngine ruleEngine;
    private final StripeRefundGateway stripeRefundGateway;
    private final EmailService emailService;

    public RefundService(RefundRequestRepository refundRepository, BookingRepository bookingRepository,
                         RefundRuleEngine ruleEngine, StripeRefundGateway stripeRefundGateway,
                         EmailService emailService) {
        this.refundRepository = refundRepository;
        this.bookingRepository = bookingRepository;
        this.ruleEngine = ruleEngine;
        this.stripeRefundGateway = stripeRefundGateway;
        this.emailService = emailService;
    }

    public RefundRequest requestRefund(Long bookingId, RefundRequestCreateDto request, User customer) {
        requireCustomer(customer);
        Booking booking = booking(bookingId);
        if (!customer.getId().equals(booking.getCustomerId())) throw new RuntimeException("You cannot request a refund for another customer's booking");
        if (request.getReason() == null || request.getReason().isBlank()) throw new RuntimeException("Refund reason is required");
        if (refundRepository.existsByBookingIdAndRefundStatusIn(bookingId, ACTIVE)) throw new RuntimeException("An active refund request already exists for this booking");

        BigDecimal alreadyRefunded = refundedForBooking(bookingId);
        RefundRuleEngine.RefundAssessment assessment = ruleEngine.assess(booking, alreadyRefunded);
        if (assessment.maximumRefundableAmount().signum() <= 0) throw new RuntimeException("This booking has no refundable captured payment");

        RefundRequest refund = new RefundRequest();
        refund.setBookingId(bookingId);
        refund.setCustomerId(customer.getId());
        refund.setCountry(normalizeCountry(booking.getCountry()));
        refund.setCurrency(booking.getCurrency());
        refund.setOriginalPaymentAmount(money(booking.getPaidAmount()));
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

    public List<RefundRequest> getCustomerRefunds(User customer) {
        requireCustomer(customer);
        return refundRepository.findByCustomerIdOrderByRequestedAtDesc(customer.getId());
    }

    public RefundRequest getCustomerRefund(Long id, User customer) {
        requireCustomer(customer);
        RefundRequest refund = refund(id);
        if (!customer.getId().equals(refund.getCustomerId())) throw new RuntimeException("You cannot view another customer's refund");
        return refund;
    }

    public List<RefundRequest> getAll() {
        return refundRepository.findAllByOrderByRequestedAtDesc();
    }

    public RefundRequest review(Long id, User admin) {
        requireAdmin(admin);
        RefundRequest refund = refund(id);
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
        RefundRequest refund = refund(id);
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

    public RefundRequest approveAndExecute(Long id, AdminRefundDecisionDto decision, User admin) {
        requireAdmin(admin);
        RefundRequest refund = refund(id);
        ensureDecidable(refund);
        Booking booking = booking(refund.getBookingId());
        BigDecimal requested = decision.getAmount() == null ? refund.getSuggestedMaximumRefundAmount() : decision.getAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal currentMaximum = ruleEngine.assess(booking, refundedForBooking(booking.getId())).maximumRefundableAmount()
                .min(refund.getSuggestedMaximumRefundAmount());
        if (requested.signum() <= 0 || requested.compareTo(currentMaximum) > 0) throw new RuntimeException("Approved refund exceeds the backend-validated refundable amount");

        refund.setApprovedRefundAmount(requested);
        refund.setAdminNote(blankToNull(decision.getAdminNote()));
        refund.setReviewedAt(LocalDateTime.now());
        refund.setReviewedByAdminId(admin.getId());
        refund.setRefundStatus(RefundStatus.PROCESSING);
        refund.setFailureReason(null);
        refundRepository.save(refund);

        try {
            StripeRefundGateway.StripeRefundResult result = stripeRefundGateway.refund(booking, requested, "gos-refund-" + refund.getId());
            refund.setStripePaymentIntentId(result.paymentIntentId());
            refund.setStripeRefundId(result.refundId());
            refund.setProcessedAt(LocalDateTime.now());
            refund.setRefundStatus(requested.compareTo(money(booking.getPaidAmount())) < 0
                    ? RefundStatus.PARTIALLY_REFUNDED : RefundStatus.REFUNDED);
            RefundRequest saved = refundRepository.save(refund);
            emailService.sendEmail(booking.getCustomerEmail(), "GeekOnSites refund processed",
                    "A " + booking.getCurrency() + " " + requested + " refund was processed for booking GOS-" + booking.getId() + ". Your bank may require additional processing time.");
            return saved;
        } catch (RuntimeException exception) {
            refund.setRefundStatus(RefundStatus.FAILED);
            refund.setFailureReason(safeFailure(exception));
            refund.setProcessedAt(LocalDateTime.now());
            refundRepository.save(refund);
            throw exception;
        }
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
    private RefundRequest refund(Long id) { return refundRepository.findById(id).orElseThrow(() -> new RuntimeException("Refund request not found")); }
    private void requireCustomer(User user) { if (user == null || user.getRole() != Role.CUSTOMER) throw new RuntimeException("Customer authorization required"); }
    private void requireAdmin(User user) { if (user == null || user.getRole() != Role.ADMIN) throw new RuntimeException("Admin authorization required"); }
    private BigDecimal money(Double value) { return BigDecimal.valueOf(value == null ? 0 : value).setScale(2, RoundingMode.HALF_UP); }
    private String normalizeCountry(String value) { String v = value == null ? "US" : value.trim().toUpperCase(Locale.ROOT); return v.equals("GB") || v.contains("UNITED") ? "UK" : v; }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String safeFailure(RuntimeException exception) { String value = exception.getMessage(); return value == null ? "Stripe refund failed" : value.substring(0, Math.min(value.length(), 500)); }
}
