package com.geekonsites.backend.enums;

/**
 * External payment provider for a ledger transaction. GeekOnSites currently only
 * integrates Stripe; the field exists so the ledger is not hardwired to Stripe.
 */
public enum PaymentProvider {
    STRIPE
}
