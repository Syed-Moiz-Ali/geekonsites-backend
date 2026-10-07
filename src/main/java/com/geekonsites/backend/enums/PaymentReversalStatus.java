package com.geekonsites.backend.enums;

/**
 * PHASE 4 — state of the technical reversal of a quarantined excess capture.
 *
 * <p>Separate from business refund policy: an excess capture is money the backend
 * determined was not owed, so it is reversed automatically (not via the discretionary
 * RefundRequest workflow).
 */
public enum PaymentReversalStatus {
    NOT_REQUIRED,
    PENDING,
    SUCCEEDED,
    FAILED
}
