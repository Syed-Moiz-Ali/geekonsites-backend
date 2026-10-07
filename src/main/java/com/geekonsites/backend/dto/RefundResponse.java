package com.geekonsites.backend.dto;

import com.geekonsites.backend.entity.RefundRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PHASE 9 — stable refund projection. Deliberately omits Stripe provider identifiers
 * (payment intent / refund ids) and internal rule context.
 */
public record RefundResponse(
        Long id,
        Long bookingId,
        Long customerId,
        String country,
        String currency,
        BigDecimal originalPaymentAmount,
        BigDecimal requestedRefundAmount,
        BigDecimal approvedRefundAmount,
        BigDecimal suggestedMaximumRefundAmount,
        String refundReason,
        String customerMessage,
        String refundStatus,
        String adminNote,
        String failureReason,
        LocalDateTime requestedAt,
        LocalDateTime reviewedAt,
        LocalDateTime processedAt
) {
    public static RefundResponse from(RefundRequest refund) {
        return new RefundResponse(
                refund.getId(), refund.getBookingId(), refund.getCustomerId(), refund.getCountry(),
                refund.getCurrency(), refund.getOriginalPaymentAmount(), refund.getRequestedRefundAmount(),
                refund.getApprovedRefundAmount(), refund.getSuggestedMaximumRefundAmount(), refund.getRefundReason(),
                refund.getCustomerMessage(),
                refund.getRefundStatus() == null ? null : refund.getRefundStatus().name(),
                refund.getAdminNote(), refund.getFailureReason(),
                refund.getRequestedAt(), refund.getReviewedAt(), refund.getProcessedAt());
    }
}
