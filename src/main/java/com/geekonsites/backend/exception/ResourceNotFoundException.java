package com.geekonsites.backend.exception;

/** PHASE 7 — reusable not-found exception (maps to 404). */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
