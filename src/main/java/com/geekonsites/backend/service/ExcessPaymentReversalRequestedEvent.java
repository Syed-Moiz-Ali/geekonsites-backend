package com.geekonsites.backend.service;

/** PHASE 4 — request reversal of a quarantined excess capture AFTER commit. */
public record ExcessPaymentReversalRequestedEvent(Long paymentTransactionId) {}
