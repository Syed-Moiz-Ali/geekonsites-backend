package com.geekonsites.backend.dto;

import com.geekonsites.backend.enums.ServiceMode;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/** PHASE 6 — Admin update-service request (all fields optional; code is immutable). */
public record AdminServiceUpdateRequest(
        String name,
        String description,
        ServiceMode serviceMode,
        Integer sortOrder,
        Boolean active,
        @DecimalMin(value = "0.01", message = "USD price must be positive") BigDecimal usdPrice,
        @DecimalMin(value = "0.01", message = "GBP price must be positive") BigDecimal gbpPrice
) {}
