package com.geekonsites.backend.dto;

import com.geekonsites.backend.enums.ServiceMode;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** PHASE 6 — Admin create-service request. */
public record AdminServiceCreateRequest(
        @NotBlank(message = "Service code is required") String code,
        @NotBlank(message = "Service name is required") String name,
        String description,
        @NotNull(message = "Service mode is required") ServiceMode serviceMode,
        Integer sortOrder,
        Boolean active,
        @NotNull(message = "USD price is required") @DecimalMin(value = "0.01", message = "USD price must be positive") BigDecimal usdPrice,
        @NotNull(message = "GBP price is required") @DecimalMin(value = "0.01", message = "GBP price must be positive") BigDecimal gbpPrice
) {}
