package com.geekonsites.backend.entity;

import com.geekonsites.backend.enums.PaymentProvider;
import com.geekonsites.backend.enums.PaymentTransactionStatus;
import com.geekonsites.backend.enums.PaymentType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * PHASE 1 — authoritative payment-history ledger.
 *
 * <p>One row per actual payment attempt against a booking. A booking may have many
 * rows (e.g. an on-site ADVANCE followed later by a REMAINING). Unlike the legacy
 * single {@code Booking.paymentTransactionId}, rows are never overwritten, so every
 * captured Stripe session/PaymentIntent remains independently addressable for refunds.
 *
 * <p>Money is stored as exact integer minor units ({@code amountMinor}), never as a
 * floating point value. Conversion boundaries to/from the legacy {@code Double}
 * booking fields are handled in the services, not here.
 */
@Entity
@Table(name = "payment_transactions", indexes = {
        @Index(name = "idx_payment_tx_booking", columnList = "booking_id"),
        @Index(name = "idx_payment_tx_status", columnList = "status")
})
@Data
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "customer_id")
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false, length = 20)
    private PaymentType paymentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentProvider provider = PaymentProvider.STRIPE;

    /** Exact amount in the currency's smallest unit (e.g. USD/GBP cents). */
    @Column(name = "amount_minor", nullable = false)
    private Long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    /**
     * Stripe Checkout Session id. Nullable while the row is INITIATED (created before
     * the provider session exists). The PostgreSQL migration enforces uniqueness only
     * for non-null values; H2/PostgreSQL both permit multiple NULLs in a unique index.
     */
    @Column(name = "checkout_session_id", unique = true, length = 255)
    private String checkoutSessionId;

    /** Stripe PaymentIntent id, captured when the payment succeeds. */
    @Column(name = "payment_intent_id", length = 255)
    private String paymentIntentId;

    /** Hosted Stripe Checkout URL, stored to allow safe reuse of an active attempt. */
    @Column(name = "checkout_url", length = 1024)
    private String checkoutUrl;

    /** Authoritative Stripe Checkout expiry, used to detect stale attempts. */
    @Column(name = "checkout_expires_at")
    private LocalDateTime checkoutExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentTransactionStatus status;

    /**
     * PHASE 3 — true when this is a genuine captured payment that exceeds the booking's
     * remaining obligation (a stale/duplicate capture). Such a row is preserved as
     * financial history but is excluded from the booking's paid aggregate and never
     * drives lifecycle, invoice or provisioning side effects.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean excess = false;

    /** PHASE 4 — state of the automatic technical reversal of an excess capture. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reversal_status", length = 20)
    private com.geekonsites.backend.enums.PaymentReversalStatus reversalStatus =
            com.geekonsites.backend.enums.PaymentReversalStatus.NOT_REQUIRED;

    @Column(name = "reversal_refund_id", length = 255)
    private String reversalRefundId;

    @Column(name = "reversal_error", length = 500)
    private String reversalError;

    @Column(name = "reversal_attempted_at")
    private LocalDateTime reversalAttemptedAt;

    // PHASE 9 — bounded retry bookkeeping for scheduled recovery.
    @Column(name = "reversal_attempts", nullable = false, columnDefinition = "integer default 0")
    private int reversalAttempts = 0;

    @Column(name = "reversal_next_attempt_at")
    private LocalDateTime reversalNextAttemptAt;

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
        if (status == null) status = PaymentTransactionStatus.INITIATED;
        if (provider == null) provider = PaymentProvider.STRIPE;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
