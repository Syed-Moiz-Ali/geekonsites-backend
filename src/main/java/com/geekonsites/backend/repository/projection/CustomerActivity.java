package com.geekonsites.backend.repository.projection;

import java.time.LocalDateTime;

/**
 * PHASE 9 — grouped aggregate (one row per customer) used by the CRM so it never loads
 * an entire table into memory nor issues one query per customer.
 */
public record CustomerActivity(Long customerId, LocalDateTime lastAt) {
}
