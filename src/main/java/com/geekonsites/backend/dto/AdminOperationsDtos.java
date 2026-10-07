package com.geekonsites.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

/** PHASE 9 — minimal ADMIN-only operational visibility for failed critical side effects. */
public final class AdminOperationsDtos {
    private AdminOperationsDtos() {
    }

    /** Safe, secret-free view of one failed/pending operation. */
    public record FailureItem(
            Long id,
            String type,
            String status,
            int attempts,
            LocalDateTime nextAttemptAt,
            String lastError
    ) {
    }

    public record FailuresResponse(
            List<FailureItem> items,
            long excessReversalFailures,
            long remoteProvisioningFailures,
            long pendingRefundExecutions
    ) {
    }
}
