package com.geekonsites.backend.service;

import com.stripe.Stripe;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * PHASE 3 — Stripe implementation of {@link StripeCheckoutGateway}.
 *
 * <p>Provider idempotency: the Stripe request idempotency key is
 * {@code gos-checkout-<paymentTransactionId>}, so a retried attempt for the SAME
 * internal payment transaction cannot create a second Stripe Session (e.g. after an
 * HTTP timeout where the session was actually created). A genuinely new attempt gets a
 * new PaymentTransaction id and therefore a new key.
 *
 * <p>Only synchronous card Checkout is configured ({@code Mode.PAYMENT}, default
 * payment methods), so no asynchronous payment-method events are produced.
 */
@Service
public class StripeCheckoutGatewayImpl implements StripeCheckoutGateway {

    private static final String IDEMPOTENCY_PREFIX = "gos-checkout-";

    @Value("${stripe.secret.key}")
    private String stripeSecretKey;

    @Override
    public CheckoutSession createCheckoutSession(CheckoutRequest request) {
        try {
            Stripe.apiKey = stripeSecretKey;

            SessionCreateParams params =
                    SessionCreateParams.builder()
                            .setMode(SessionCreateParams.Mode.PAYMENT)
                            .setSuccessUrl(request.successUrl())
                            .setCancelUrl(request.cancelUrl())
                            .addLineItem(
                                    SessionCreateParams.LineItem.builder()
                                            .setQuantity(1L)
                                            .setPriceData(
                                                    SessionCreateParams.LineItem.PriceData.builder()
                                                            .setCurrency(request.currency().toLowerCase())
                                                            .setUnitAmount(request.amountMinor())
                                                            .setProductData(
                                                                    SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                                            .setName(request.productName())
                                                                            .setDescription(request.description())
                                                                            .build()
                                                            )
                                                            .build()
                                            )
                                            .build()
                            )
                            .putMetadata("bookingId", String.valueOf(request.bookingId()))
                            .putMetadata("paymentType", request.paymentType())
                            .putMetadata("paymentTransactionId", String.valueOf(request.paymentTransactionId()))
                            .build();

            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey(IDEMPOTENCY_PREFIX + request.paymentTransactionId())
                    .build();

            Session session = Session.create(params, options);

            LocalDateTime expiresAt = session.getExpiresAt() == null
                    ? null
                    : LocalDateTime.ofInstant(Instant.ofEpochSecond(session.getExpiresAt()), ZoneId.systemDefault());

            return new CheckoutSession(session.getId(), session.getUrl(), expiresAt);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to create Stripe checkout session: " + exception.getMessage(), exception);
        }
    }
}
