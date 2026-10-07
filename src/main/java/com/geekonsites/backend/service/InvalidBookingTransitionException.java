package com.geekonsites.backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * PHASE 2 — raised when a requested booking lifecycle action is not legal from the
 * booking's current state.
 *
 * <p>Extends {@link ResponseStatusException} so it is already mapped to a proper HTTP
 * status (typically 409 Conflict for an illegal transition, 400 Bad Request for a
 * malformed action) by Spring's existing exception handling without introducing a
 * global error contract in this phase.
 */
public class InvalidBookingTransitionException extends ResponseStatusException {

    public InvalidBookingTransitionException(HttpStatus status, String reason) {
        super(status, reason);
    }
}
