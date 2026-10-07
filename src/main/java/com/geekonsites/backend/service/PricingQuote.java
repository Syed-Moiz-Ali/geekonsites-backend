package com.geekonsites.backend.service;

import com.geekonsites.backend.enums.Currency;
import com.geekonsites.backend.enums.ServiceMode;

/**
 * PHASE 6 — internal, server-authoritative pricing result for a booking request.
 * Exact minor units; snapshotted onto the Booking. This is NOT the client "free quote" feature.
 */
public record PricingQuote(
        Long serviceId,
        String serviceCode,
        String serviceName,
        ServiceMode serviceMode,
        Currency currency,
        long baseAmountMinor,
        long addonAmountMinor,
        long protectionAmountMinor,
        long platformFeeMinor,
        long totalAmountMinor,
        long advanceAmountMinor,
        long remainingAmountMinor,
        String paymentType,
        String selectedAddons
) {}
