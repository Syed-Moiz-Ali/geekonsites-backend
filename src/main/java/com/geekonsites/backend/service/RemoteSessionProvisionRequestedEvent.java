package com.geekonsites.backend.service;

/** PHASE 4 — request remote-session provisioning AFTER the payment transaction commits. */
public record RemoteSessionProvisionRequestedEvent(Long bookingId) {}
