package com.geekonsites.backend.service;

/** PHASE 4 — non-critical assignment side effects (notifications, calendar sync) AFTER commit. */
public record BookingAssignedEvent(Long bookingId, Long technicianId) {}
