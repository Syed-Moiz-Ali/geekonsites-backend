package com.geekonsites.backend.dto;

import com.geekonsites.backend.enums.Currency;
import com.geekonsites.backend.enums.ServiceMode;

import java.math.BigDecimal;

/** PHASE 6 — public service catalog response (customer-facing money as decimal). */
public record ServiceResponse(
        Long id,
        String code,
        String name,
        String description,
        ServiceMode serviceMode,
        BigDecimal price,
        Currency currency
) {}
