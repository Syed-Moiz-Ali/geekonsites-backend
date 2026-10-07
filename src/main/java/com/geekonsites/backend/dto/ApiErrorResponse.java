package com.geekonsites.backend.dto;

import java.util.List;

/**
 * PHASE 7 — the single standard API error contract used by every failure response.
 * Never includes stack traces, exception class names, SQL, or provider secrets.
 */
public record ApiErrorResponse(
        String timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        List<FieldError> fieldErrors
) {
    public record FieldError(String field, String message) {}
}
