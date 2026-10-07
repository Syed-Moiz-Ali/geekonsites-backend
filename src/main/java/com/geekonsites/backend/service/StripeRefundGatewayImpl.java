package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.PaymentTransaction;
import com.stripe.Stripe;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * PHASE 1 — Stripe implementation of a per-transaction refund.
 *
 * <p>Every refund is executed against the exact PaymentIntent recorded on the ledger
 * row, with the amount validated against that PaymentIntent's remaining captured
 * amount. Idempotency keys are supplied by the caller and persisted with the
 * {@code PaymentRefund} row.
 */
@Service
public class StripeRefundGatewayImpl implements StripeRefundGateway {
    @Value("${stripe.secret.key}")
    private String stripeSecretKey;

    @Override
    public StripeRefundResult refundPaymentTransaction(PaymentTransaction transaction, long amountMinor, String idempotencyKey) {
        try {
            Stripe.apiKey = stripeSecretKey;
            String paymentIntentId = transaction.getPaymentIntentId();
            if (paymentIntentId == null || paymentIntentId.isBlank()) {
                throw new RuntimeException("Payment transaction has no Stripe PaymentIntent");
            }

            PaymentIntent paymentIntent = PaymentIntent.retrieve(paymentIntentId);
            long captured = paymentIntent.getAmountReceived() == null ? 0 : paymentIntent.getAmountReceived();
            long alreadyRefunded = 0;
            if (paymentIntent.getLatestCharge() != null) {
                Charge charge = Charge.retrieve(paymentIntent.getLatestCharge());
                alreadyRefunded = charge.getAmountRefunded() == null ? 0 : charge.getAmountRefunded();
            }
            if (amountMinor <= 0 || amountMinor > captured - alreadyRefunded) {
                throw new RuntimeException("Refund exceeds the remaining captured Stripe amount");
            }

            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(paymentIntent.getId())
                    .setAmount(amountMinor)
                    .putMetadata("bookingId", String.valueOf(transaction.getBookingId()))
                    .putMetadata("paymentTransactionId", String.valueOf(transaction.getId()))
                    .build();
            Refund refund = Refund.create(params, RequestOptions.builder().setIdempotencyKey(idempotencyKey).build());
            return new StripeRefundResult(paymentIntent.getId(), refund.getId(), refund.getStatus());
        } catch (Exception exception) {
            throw new RuntimeException("Stripe refund failed: " + exception.getMessage(), exception);
        }
    }
}
