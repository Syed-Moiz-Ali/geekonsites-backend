package com.geekonsites.backend.service;

/**
 * PHASE 3 — raised when a payment/refund provider-state transition is not legal
 * (for example, attempting to move a SUCCEEDED transaction backwards). Kept as a
 * domain exception so webhook handling can reject safely without corrupting state.
 */
public class InvalidPaymentStateException extends RuntimeException {

    public InvalidPaymentStateException(String message) {
        super(message);
    }
}
