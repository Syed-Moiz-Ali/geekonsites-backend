package com.geekonsites.backend.service;

import java.time.LocalDateTime;

/**
 * PHASE 3 — the boundary for creating Stripe Checkout Sessions.
 *
 * <p>Extracted so the payment-orchestration logic is testable without a live Stripe
 * account, and so provider idempotency is applied in exactly one place.
 */
public interface StripeCheckoutGateway {

    CheckoutSession createCheckoutSession(CheckoutRequest request);

    /**
     * @param paymentTransactionId ties the Stripe idempotency key to one internal
     *                             payment attempt (never a permanent booking/type key)
     */
    record CheckoutRequest(
            Long paymentTransactionId,
            Long bookingId,
            String productName,
            String description,
            long amountMinor,
            String currency,
            String successUrl,
            String cancelUrl,
            String paymentType
    ) {}

    record CheckoutSession(String sessionId, String url, LocalDateTime expiresAt) {}
}
