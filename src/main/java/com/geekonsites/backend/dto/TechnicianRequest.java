package com.geekonsites.backend.dto;

import lombok.Data;

@Data
public class TechnicianRequest {

    private String name;
    private String email;
    private String password;
    private String phone;
    private String country;
    private String city;
    private String specialization;
    private Integer experienceYears;
    private String citizenshipStatus;
    private String identityDocumentType;
    private String identityDocumentName;
    private String identityDocumentData;
    private String livePhotoData;
    private String employmentType;
    private String serviceMode;
    private String workAuthorizationType;
    private String workAuthorizationExpiry;
    private String workAuthorizationDocumentName;
    private String workAuthorizationDocumentData;
    private String addressHistory;
    private String addressProofName;
    private String addressProofData;
    private String drivingLicenseName;
    private String drivingLicenseData;
    private String vehicleInsuranceName;
    private String vehicleInsuranceData;
    private String publicLiabilityName;
    private String publicLiabilityData;
}
