package com.geekonsites.backend.entity;

import com.geekonsites.backend.enums.RefundStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "refund_requests", indexes = {
        @Index(name = "idx_refund_booking", columnList = "booking_id"),
        @Index(name = "idx_refund_customer", columnList = "customer_id"),
        @Index(name = "idx_refund_status", columnList = "refund_status")
})
@Data
public class RefundRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;
    @Column(name = "customer_id", nullable = false)
    private Long customerId;
    @Column(nullable = false, length = 2)
    private String country;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal originalPaymentAmount;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal requestedRefundAmount;
    @Column(precision = 12, scale = 2)
    private BigDecimal approvedRefundAmount;
    @Column(nullable = false, length = 120)
    private String refundReason;
    @Column(columnDefinition = "TEXT")
    private String customerMessage;
    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", nullable = false, length = 32)
    private RefundStatus refundStatus;
    private String stripePaymentIntentId;
    private String stripeRefundId;
    @Column(nullable = false)
    private LocalDateTime requestedAt;
    private LocalDateTime reviewedAt;
    private LocalDateTime processedAt;
    private Long reviewedByAdminId;
    @Column(columnDefinition = "TEXT")
    private String adminNote;
    @Column(columnDefinition = "TEXT", nullable = false)
    private String ruleContext;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal suggestedMaximumRefundAmount;
    @Column(columnDefinition = "TEXT")
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (refundStatus == null) refundStatus = RefundStatus.REQUESTED;
        if (requestedAt == null) requestedAt = now;
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
