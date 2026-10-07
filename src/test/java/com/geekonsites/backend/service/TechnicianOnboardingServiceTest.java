package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.TechnicianAdminResponse;
import com.geekonsites.backend.dto.TechnicianSetPasswordRequest;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.TechnicianOnboardingToken;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.TechnicianOnboardingStatus;
import com.geekonsites.backend.repository.TechnicianOnboardingTokenRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TechnicianOnboardingServiceTest {
    private TechnicianRepository technicians;
    private UserRepository users;
    private TechnicianOnboardingTokenRepository tokens;
    private ApplicationEventPublisher events;
    private BCryptPasswordEncoder encoder;
    private TechnicianService service;

    @BeforeEach
    void setUp() {
        technicians = mock(TechnicianRepository.class);
        users = mock(UserRepository.class);
        tokens = mock(TechnicianOnboardingTokenRepository.class);
        events = mock(ApplicationEventPublisher.class);
        encoder = new BCryptPasswordEncoder(4);
        service = new TechnicianService(technicians, users, encoder, tokens, events);
        when(technicians.save(any())).thenAnswer(call -> call.getArgument(0));
        when(users.save(any())).thenAnswer(call -> call.getArgument(0));
        when(tokens.save(any())).thenAnswer(call -> call.getArgument(0));
        when(technicians.existsByIdAndVerificationStatus(1L, "APPROVED")).thenReturn(true);
    }

    @Test
    void approvalKeepsPersonalEmailAsLoginEmailAndSendsApprovalEmailWithoutIssuingAToken() {
        Technician technician = pending("Rahul Sharma", "rahul.personal@example.com");
        User user = user("rahul.personal@example.com");
        when(technicians.findById(1L)).thenReturn(Optional.of(technician));
        when(users.findByEmailIgnoreCase("rahul.personal@example.com")).thenReturn(Optional.of(user));

        TechnicianAdminResponse response = service.approveTechnician(1L);

        // No @gos.com account is issued: the technician keeps signing in with
        // the personal email and password they created at registration.
        assertNull(response.companyEmail());
        assertEquals("rahul.personal@example.com", response.personalEmail());
        assertEquals("APPROVED", response.verificationStatus());
        assertEquals("AVAILABLE", response.availabilityStatus());
        assertEquals(TechnicianOnboardingStatus.NOT_STARTED, response.onboardingStatus());
        assertEquals("rahul.personal@example.com", user.getEmail());
        assertEquals("old-hash", user.getPassword());

        ArgumentCaptor<TechnicianApprovalEmailEvent> event = ArgumentCaptor.forClass(TechnicianApprovalEmailEvent.class);
        verify(events).publishEvent(event.capture());
        assertEquals("rahul.personal@example.com", event.getValue().personalEmail());
        assertEquals("Rahul Sharma", event.getValue().technicianName());
        verify(tokens, never()).save(any());
    }

    @Test
    void repeatedApprovalIsIdempotentAndDoesNotResendTheApprovalEmail() {
        Technician technician = pending("Rahul Patel", "second@example.com");
        User user = user("second@example.com");
        when(technicians.findById(1L)).thenReturn(Optional.of(technician));
        when(users.findByEmailIgnoreCase("second@example.com")).thenReturn(Optional.of(user));

        TechnicianAdminResponse first = service.approveTechnician(1L);
        TechnicianAdminResponse repeated = service.approveTechnician(1L);

        assertEquals("second@example.com", first.personalEmail());
        assertEquals(first.personalEmail(), repeated.personalEmail());
        verify(users, times(1)).save(any());
        verify(events, times(1)).publishEvent(any(TechnicianApprovalEmailEvent.class));
    }

    @Test
    void resendSendsApprovalNoticeToPersonalEmailWithoutCreatingSetupToken() {
        Technician technician = pending("Rahul Sharma", "personal@example.com");
        technician.setVerificationStatus("APPROVED");
        technician.setCompanyEmail("rahul@gos.com");
        technician.setOnboardingStatus(TechnicianOnboardingStatus.EMAIL_FAILED);
        when(technicians.findById(1L)).thenReturn(Optional.of(technician));
        TechnicianAdminResponse response = service.resendOnboarding(1L);

        assertEquals("rahul@gos.com", response.companyEmail());
        verify(events).publishEvent(new TechnicianApprovalEmailEvent(1L, "personal@example.com", "Rahul Sharma"));
        verifyNoInteractions(tokens);
    }

    @Test
    void passwordSetupHashesPasswordMarksCompleteAndTokenCannotBeReused() {
        User user = user("rahul@gos.com");
        TechnicianOnboardingToken token = token(user, Instant.now().plusSeconds(3600), false);
        when(tokens.findByTokenHash(sha256("raw-token"))).thenReturn(Optional.of(token));

        var response = service.setOnboardingPassword(new TechnicianSetPasswordRequest("raw-token", "SecurePass1!"));

        assertEquals("rahul@gos.com", response.companyEmail());
        assertTrue(encoder.matches("SecurePass1!", user.getPassword()));
        assertTrue(token.isUsed());
        verify(technicians).markPasswordSetupComplete(eq(1L), any(Instant.class));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.setOnboardingPassword(new TechnicianSetPasswordRequest("raw-token", "SecurePass1!")));
    }

    @Test
    void expiredTokenIsRejected() {
        TechnicianOnboardingToken token = token(user("rahul@gos.com"), Instant.now().minusSeconds(1), false);
        when(tokens.findByTokenHash(sha256("expired"))).thenReturn(Optional.of(token));
        org.springframework.web.server.ResponseStatusException exception =
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.setOnboardingPassword(new TechnicianSetPasswordRequest("expired", "SecurePass1!")));
        assertTrue(exception.getReason().contains("expired"));
        assertTrue(token.isUsed());
    }

    @Test
    void rejectedTechnicianCannotUsePreviouslyIssuedSetupToken() {
        TechnicianOnboardingToken token = token(user("rahul@gos.com"), Instant.now().plusSeconds(3600), false);
        when(tokens.findByTokenHash(sha256("raw-token"))).thenReturn(Optional.of(token));
        when(technicians.existsByIdAndVerificationStatus(1L, "APPROVED")).thenReturn(false);

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.setOnboardingPassword(new TechnicianSetPasswordRequest("raw-token", "SecurePass1!")));
        verify(users, never()).save(any());
    }

    private Technician pending(String name, String email) {
        Technician value = new Technician();
        value.setId(1L); value.setName(name); value.setEmail(email); value.setPersonalEmail(email);
        value.setVerificationStatus("PENDING"); value.setAvailabilityStatus("UNAVAILABLE");
        value.setServiceMode("REMOTE_ONLY"); value.setIdentityDocumentData("evidence");
        value.setLivePhotoData("photo"); value.setAddressProofData("address"); value.setWorkAuthorizationType("CITIZEN");
        value.setOnboardingStatus(TechnicianOnboardingStatus.NOT_STARTED);
        return value;
    }

    private User user(String email) {
        User value = new User(); value.setId(9L); value.setEmail(email); value.setPassword("old-hash"); value.setRole(Role.TECHNICIAN); return value;
    }

    private TechnicianOnboardingToken token(User user, Instant expiry, boolean used) {
        TechnicianOnboardingToken value = new TechnicianOnboardingToken(); value.setTechnicianId(1L); value.setUser(user); value.setExpiresAt(expiry); value.setUsed(used); return value;
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new RuntimeException(exception); }
    }
}
