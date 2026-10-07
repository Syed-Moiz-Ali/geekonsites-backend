package com.geekonsites.backend.enums;

/**
 * PHASE 9 — explicit business semantics for a notification.
 *
 * <p>The notification {@code type} must never be inferred from its display title. Titles
 * and bodies are presentation; this enum is the durable business meaning, persisted as
 * {@code STRING} on {@code notifications.type}.
 */
public enum NotificationType {
    BOOKING_CREATED,
    PAYMENT_SUCCESS,
    TECHNICIAN_ASSIGNED,
    TECHNICIAN_ACCEPTED,
    TECHNICIAN_REJECTED,
    TECHNICIAN_ON_THE_WAY,
    TECHNICIAN_ARRIVED,
    SERVICE_STARTED,
    SERVICE_COMPLETED,
    REMOTE_SESSION_READY,
    MEETING_LINK_READY,
    REMAINING_PAYMENT_REQUIRED,
    INVOICE_GENERATED,
    BOOKING_CLOSED,
    BOOKING_UPDATE
}
