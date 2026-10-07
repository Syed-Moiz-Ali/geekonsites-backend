package com.geekonsites.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * PHASE 3 — the single canonical boundary between the legacy {@code Double} booking
 * money fields and the exact integer minor-unit payment ledger.
 *
 * <p>No other class should perform {@code Math.round(amount * 100)} or currency-case
 * handling. USD/GBP are two-decimal currencies, so conversion is deterministic.
 */
public final class PaymentMoney {

    private PaymentMoney() {
    }

    /** Converts a legacy Double major-unit amount to exact minor units. */
    public static long toMinor(Double amount) {
        double value = amount == null ? 0.0 : amount;
        return Math.round(value * 100.0);
    }

    /** Converts an exact decimal amount to minor units. */
    public static long toMinor(BigDecimal amount) {
        return amount == null
                ? 0L
                : amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Converts minor units back to a legacy Double major-unit amount. */
    public static double toMajor(long amountMinor) {
        return amountMinor / 100.0;
    }

    /**
     * PHASE 8 — resolves the authoritative exact minor amount. Prefers the exact
     * {@code *Minor} column; only falls back to converting the deprecated Double
     * mirror when the exact value is absent (legacy/transient rows).
     */
    public static long resolveMinor(Long amountMinor, Double legacyMajorAmount) {
        return amountMinor != null ? amountMinor : toMinor(legacyMajorAmount);
    }

    /** Converts minor units back to an exact decimal amount. */
    public static BigDecimal toMajorMoney(long amountMinor) {
        return BigDecimal.valueOf(amountMinor, 2);
    }

    /** Normalises a legacy Double major-unit amount to an exact 2dp decimal. */
    public static BigDecimal toMoney(Double amount) {
        double value = amount == null ? 0.0 : amount;
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** Upper-cases an ISO currency code for storage/comparison. */
    public static String normalizeCurrency(String currency) {
        return currency == null || currency.isBlank() ? "USD" : currency.trim().toUpperCase(Locale.ROOT);
    }

    /** Case-insensitive currency equality (Stripe returns lower-case). */
    public static boolean sameCurrency(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }
}
