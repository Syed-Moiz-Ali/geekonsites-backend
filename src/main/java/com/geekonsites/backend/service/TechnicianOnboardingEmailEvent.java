package com.geekonsites.backend.service;

public record TechnicianOnboardingEmailEvent(
        Long technicianId,
        String personalEmail,
        String technicianName,
        String companyEmail,
        String rawToken
) {
}
