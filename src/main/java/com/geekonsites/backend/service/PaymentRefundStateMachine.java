package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.PaymentRefund;
import com.geekonsites.backend.enums.PaymentRefundStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * PHASE 3 — the single authority for {@link PaymentRefund} execution-state transitions.
 *
 * <p>{@code SUCCEEDED} is terminal: a stale/failed provider update can never downgrade a
 * successful refund. The provider refund id is immutable once set, and execution
 * amount/currency are frozen by the caller.
 */
@Service
public class PaymentRefundStateMachine {

    public boolean markSucceeded(PaymentRefund refund, String providerRefundId) {
        if (refund.getStatus() == PaymentRefundStatus.SUCCEEDED) {
            return false;
        }
        if (providerRefundId != null && refund.getProviderRefundId() != null
                && !refund.getProviderRefundId().equals(providerRefundId)) {
            throw new InvalidPaymentStateException("The provider refund id for this execution cannot change");
        }
        if (providerRefundId != null) {
            refund.setProviderRefundId(providerRefundId);
        }
        refund.setStatus(PaymentRefundStatus.SUCCEEDED);
        if (refund.getCompletedAt() == null) {
            refund.setCompletedAt(LocalDateTime.now());
        }
        return true;
    }

    public boolean markFailed(PaymentRefund refund) {
        if (refund.getStatus() == PaymentRefundStatus.SUCCEEDED
                || refund.getStatus() == PaymentRefundStatus.FAILED) {
            return false;
        }
        refund.setStatus(PaymentRefundStatus.FAILED);
        if (refund.getCompletedAt() == null) {
            refund.setCompletedAt(LocalDateTime.now());
        }
        return true;
    }
}
