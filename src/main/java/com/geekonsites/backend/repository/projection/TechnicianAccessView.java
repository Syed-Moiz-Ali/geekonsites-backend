package com.geekonsites.backend.repository.projection;

import com.geekonsites.backend.enums.TechnicianOnboardingStatus;

public interface TechnicianAccessView {
    Long getId();
    String getEmail();
    String getPersonalEmail();
    String getCompanyEmail();
    String getVerificationStatus();
    TechnicianOnboardingStatus getOnboardingStatus();
    String getAvailabilityStatus();
    String getServiceMode();
}
