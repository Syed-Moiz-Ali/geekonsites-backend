package com.geekonsites.backend.service;

public record TechnicianApprovalEmailEvent(
        Long technicianId,
        String personalEmail,
        String technicianName
) {
}
