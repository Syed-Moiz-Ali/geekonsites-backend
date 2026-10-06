package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.stripe.Stripe;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class StripeRefundGatewayImpl implements StripeRefundGateway {
    @Value("${stripe.secret.key}")
    private String stripeSecretKey;

    @Override
    public StripeRefundResult refund(Booking booking, BigDecimal amount, String idempotencyKey) {
        try {
            Stripe.apiKey = stripeSecretKey;
            String sessionId = booking.getPaymentTransactionId();
            if (sessionId == null || sessionId.isBlank()) throw new RuntimeException("Booking has no Stripe payment reference");

            Session session = Session.retrieve(sessionId);
            if (!"paid".equalsIgnoreCase(session.getPaymentStatus())) throw new RuntimeException("Original Stripe payment is not successful");
            if (!String.valueOf(booking.getId()).equals(session.getMetadata().get("bookingId"))) throw new RuntimeException("Stripe payment does not belong to this booking");
            if (session.getCurrency() == null || !session.getCurrency().equalsIgnoreCase(booking.getCurrency())) throw new RuntimeException("Stripe payment currency does not match booking currency");
            if (session.getPaymentIntent() == null) throw new RuntimeException("Stripe PaymentIntent is missing");

            PaymentIntent paymentIntent = PaymentIntent.retrieve(session.getPaymentIntent());
            long requested = amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            long captured = paymentIntent.getAmountReceived() == null ? 0 : paymentIntent.getAmountReceived();
            long alreadyRefunded = 0;
            if (paymentIntent.getLatestCharge() != null) {
                Charge charge = Charge.retrieve(paymentIntent.getLatestCharge());
                alreadyRefunded = charge.getAmountRefunded() == null ? 0 : charge.getAmountRefunded();
            }
            if (requested <= 0 || requested > captured - alreadyRefunded) throw new RuntimeException("Refund exceeds the remaining captured Stripe amount");

            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(paymentIntent.getId())
                    .setAmount(requested)
                    .putMetadata("bookingId", String.valueOf(booking.getId()))
                    .build();
            Refund refund = Refund.create(params, RequestOptions.builder().setIdempotencyKey(idempotencyKey).build());
            return new StripeRefundResult(paymentIntent.getId(), refund.getId(), refund.getStatus());
        } catch (Exception exception) {
            throw new RuntimeException("Stripe refund failed: " + exception.getMessage(), exception);
        }
    }
}
