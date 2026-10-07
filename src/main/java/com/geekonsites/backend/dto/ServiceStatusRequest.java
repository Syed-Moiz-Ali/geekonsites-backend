package com.geekonsites.backend.dto;

import jakarta.validation.constraints.NotNull;

/** PHASE 6 — Admin activate/deactivate request. */
public record ServiceStatusRequest(@NotNull(message = "active is required") Boolean active) {}
