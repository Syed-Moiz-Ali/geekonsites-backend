package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.TechnicianRegistrationResponse;
import com.geekonsites.backend.dto.TechnicianRequest;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.repository.UserRepository;
import com.geekonsites.backend.repository.TechnicianOnboardingTokenRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TechnicianRegistrationServiceTest {

    private TechnicianRepository technicians;
    private UserRepository users;
    private PasswordEncoder passwordEncoder;
    private TechnicianService service;

    @BeforeEach
    void setUp() {
        technicians = mock(TechnicianRepository.class);
        users = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new TechnicianService(
                technicians, users, passwordEncoder,
                mock(TechnicianOnboardingTokenRepository.class), mock(ApplicationEventPublisher.class)
        );

        when(technicians.existsByEmailIgnoreCase(any())).thenReturn(false);
        when(users.existsByEmailIgnoreCase(any())).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("encoded-password");
        when(technicians.save(any())).thenAnswer(invocation -> {
            Technician technician = invocation.getArgument(0);
            technician.setId(42L);
            return technician;
        });
    }

    @Test
    void registrationStoresEvidenceAndReturnsOnlyRegistrationSummary() {
        TechnicianRequest request = validRemoteRequest();

        TechnicianRegistrationResponse response = service.createTechnician(request);

        assertEquals(42L, response.applicationId());
        assertEquals("PENDING", response.verificationStatus());
        assertEquals("REMOTE_ONLY", response.serviceMode());
        assertTrue(response.message().contains("HR Review Pending"));

        ArgumentCaptor<Technician> saved = ArgumentCaptor.forClass(Technician.class);
        verify(technicians).save(saved.capture());
        assertEquals(request.getIdentityDocumentData(), saved.getValue().getIdentityDocumentData());
        assertEquals(request.getLivePhotoData(), saved.getValue().getLivePhotoData());
        assertEquals(request.getAddressProofData(), saved.getValue().getAddressProofData());
        verify(users).saveAndFlush(any());
    }

    @Test
    void duplicateEmailIsPreventedBeforeHashingOrPersistence() {
        TechnicianRequest request = validRemoteRequest();
        when(technicians.existsByEmailIgnoreCase("applicant@example.com")).thenReturn(true);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> service.createTechnician(request));

        assertEquals("An account already exists for this email", exception.getMessage());
        verifyNoInteractions(passwordEncoder);
        verify(technicians, never()).save(any());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void oversizedEvidenceIsRejectedBeforeDatabaseWork() {
        TechnicianRequest request = validRemoteRequest();
        request.setIdentityDocumentData("data:image/jpeg;base64," + "A".repeat(7_000_000));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> service.createTechnician(request));

        assertTrue(exception.getMessage().contains("5 MB or smaller"));
        verify(technicians, never()).save(any());
    }

    private TechnicianRequest validRemoteRequest() {
        TechnicianRequest request = new TechnicianRequest();
        request.setName("Test Applicant");
        request.setEmail("Applicant@Example.com");
        request.setPassword("ValidPass1!");
        request.setPhone("+441234567890");
        request.setCountry("UK");
        request.setCity("London");
        request.setCitizenshipStatus("CITIZEN");
        request.setIdentityDocumentType("GOVERNMENT_ID");
        request.setIdentityDocumentData("data:image/jpeg;base64,AQID");
        request.setLivePhotoData("data:image/jpeg;base64,AQID");
        request.setEmploymentType("INDEPENDENT_CONTRACTOR");
        request.setServiceMode("REMOTE_ONLY");
        request.setWorkAuthorizationType("CITIZEN");
        request.setAddressHistory("Current address");
        request.setAddressProofData("data:application/pdf;base64,AQID");
        return request;
    }
}
