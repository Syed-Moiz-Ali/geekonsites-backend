package com.geekonsites.backend.dto;

import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.enums.TechnicianOnboardingStatus;

import java.time.Instant;

public record TechnicianAdminResponse(
        Long id,
        String name,
        String personalEmail,
        String companyEmail,
        String verificationStatus,
        String availabilityStatus,
        String serviceMode,
        TechnicianOnboardingStatus onboardingStatus,
        Instant companyEmailAssignedAt,
        Instant onboardingEmailSentAt,
        Instant passwordSetupCompletedAt
) {
    public static TechnicianAdminResponse from(Technician technician) {
        return new TechnicianAdminResponse(
                technician.getId(), technician.getName(),
                technician.getPersonalEmail() == null ? technician.getEmail() : technician.getPersonalEmail(),
                technician.getCompanyEmail(), technician.getVerificationStatus(),
                technician.getAvailabilityStatus(), technician.getServiceMode(),
                technician.getOnboardingStatus(), technician.getCompanyEmailAssignedAt(),
                technician.getOnboardingEmailSentAt(), technician.getPasswordSetupCompletedAt()
        );
    }
}
