package com.geekonsites.backend.entity;

import com.geekonsites.backend.enums.PaymentRefundStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * PHASE 1 — refund-execution ledger.
 *
 * <p>One row per refund executed against one specific {@link PaymentTransaction}. A
 * single {@code RefundRequest} (e.g. "refund 100") can therefore be satisfied by
 * multiple rows — 70 against the REMAINING transaction and 30 against the ADVANCE
 * transaction — instead of attempting a single 100 refund against a PaymentIntent that
 * only captured 70.
 *
 * <p>{@code amountMinor} is exact integer minor units. {@code idempotencyKey} is unique
 * so a retried refund cannot create a second execution for the same
 * (request, transaction) pair.
 */
@Entity
@Table(name = "payment_refunds", indexes = {
        @Index(name = "idx_payment_refund_request", columnList = "refund_request_id"),
        @Index(name = "idx_payment_refund_tx", columnList = "payment_transaction_id")
})
@Data
public class PaymentRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_request_id", nullable = false)
    private Long refundRequestId;

    @Column(name = "payment_transaction_id", nullable = false)
    private Long paymentTransactionId;

    @Column(name = "provider_refund_id", unique = true, length = 255)
    private String providerRefundId;

    @Column(name = "payment_intent_id", length = 255)
    private String paymentIntentId;

    @Column(name = "amount_minor", nullable = false)
    private Long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentRefundStatus status;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 160)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = PaymentRefundStatus.PENDING;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
