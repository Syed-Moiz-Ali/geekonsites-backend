package com.geekonsites.backend.dto;

public record TechnicianRegistrationResponse(
        Long applicationId,
        String name,
        String email,
        String verificationStatus,
        String serviceMode,
        String message
) {
}
