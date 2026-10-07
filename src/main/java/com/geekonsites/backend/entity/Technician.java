package com.geekonsites.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.geekonsites.backend.enums.TechnicianOnboardingStatus;
import java.time.Instant;

@Entity
@Table(name = "technicians")
@Data
public class Technician {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(unique = true)
    private String email;

    private String personalEmail;

    @Column(unique = true)
    private String companyEmail;

    @Enumerated(EnumType.STRING)
    private TechnicianOnboardingStatus onboardingStatus;

    private Instant companyEmailAssignedAt;
    private Instant onboardingEmailSentAt;
    private Instant passwordSetupCompletedAt;

    private String phone;

    private String country;

    private String city;

    private String specialization;

    private Integer experienceYears;

    private String availabilityStatus;

    private String verificationStatus;

    private Double rating;

    private String citizenshipStatus;
    private String identityDocumentType;
    private String identityDocumentName;

    // PHASE 9 E2E fix: PostgreSQL TEXT columns are mapped as plain String, not @Lob,
    // so reads do not fail with "Unable to access lob stream" (e.g. GET /api/technicians/pending).
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String identityDocumentData;

    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String livePhotoData;

    private String employmentType;
    private String serviceMode;
    private String workAuthorizationType;
    private String workAuthorizationExpiry;
    private String workAuthorizationDocumentName;
    private String addressHistory;
    private String addressProofName;
    private String drivingLicenseName;
    private String vehicleInsuranceName;
    private String publicLiabilityName;

    @JsonIgnore @Column(columnDefinition = "TEXT")
    private String workAuthorizationDocumentData;
    @JsonIgnore @Column(columnDefinition = "TEXT")
    private String addressProofData;
    @JsonIgnore @Column(columnDefinition = "TEXT")
    private String drivingLicenseData;
    @JsonIgnore @Column(columnDefinition = "TEXT")
    private String vehicleInsuranceData;
    @JsonIgnore @Column(columnDefinition = "TEXT")
    private String publicLiabilityData;
}
