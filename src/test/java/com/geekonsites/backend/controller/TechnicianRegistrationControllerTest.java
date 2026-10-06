package com.geekonsites.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekonsites.backend.dto.TechnicianRegistrationResponse;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.service.BookingService;
import com.geekonsites.backend.service.NotificationService;
import com.geekonsites.backend.service.TechnicianService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TechnicianRegistrationControllerTest {

    private TechnicianService technicianService;
    private TechnicianController controller;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        technicianService = mock(TechnicianService.class);
        objectMapper = new ObjectMapper();
        controller = new TechnicianController(
                technicianService,
                mock(TechnicianRepository.class),
                mock(BookingService.class),
                mock(NotificationService.class),
                objectMapper
        );
    }

    @Test
    void registrationResponseContainsNoVerificationEvidenceFields() throws Exception {
        TechnicianRegistrationResponse response = new TechnicianRegistrationResponse(
                42L, "Test Applicant", "applicant@example.com", "PENDING", "REMOTE_ONLY", "Application received"
        );

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(response));

        assertEquals(6, json.size());
        assertEquals(42L, json.get("applicationId").asLong());
        assertFalse(json.has("identityDocumentData"));
        assertFalse(json.has("livePhotoData"));
        assertFalse(json.has("workAuthorizationDocumentData"));
        assertFalse(json.has("addressProofData"));
        assertFalse(json.has("drivingLicenseData"));
        assertFalse(json.has("vehicleInsuranceData"));
        assertFalse(json.has("publicLiabilityData"));
    }

    @Test
    void adminVerificationEndpointStillReturnsStoredEvidencePrivately() {
        Technician technician = new Technician();
        technician.setIdentityDocumentData("data:image/png;base64,AQID");
        when(technicianService.getTechnicianById(42L)).thenReturn(technician);

        ResponseEntity<byte[]> response = controller.getVerificationEvidence(42L, "identity-document");

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(new byte[]{1, 2, 3}, response.getBody());
        assertEquals("private, no-store", response.getHeaders().getFirst("Cache-Control"));
    }
}
