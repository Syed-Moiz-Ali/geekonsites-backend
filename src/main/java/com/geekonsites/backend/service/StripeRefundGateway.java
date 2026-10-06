package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;

import java.math.BigDecimal;

public interface StripeRefundGateway {
    StripeRefundResult refund(Booking booking, BigDecimal amount, String idempotencyKey);

    record StripeRefundResult(String paymentIntentId, String refundId, String status) {}
}
