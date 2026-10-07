package com.geekonsites.backend.dto;

import com.geekonsites.backend.enums.ServiceMode;

import java.math.BigDecimal;

/** PHASE 6 — Admin service response including both market prices. */
public record AdminServiceResponse(
        Long id,
        String code,
        String name,
        String description,
        ServiceMode serviceMode,
        boolean active,
        Integer sortOrder,
        BigDecimal usdPrice,
        BigDecimal gbpPrice
) {}
