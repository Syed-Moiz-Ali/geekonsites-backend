package com.geekonsites.backend.auth;

import com.geekonsites.backend.dto.LoginRequest;
import com.geekonsites.backend.dto.LoginResponse;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.jwt.JwtService;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.repository.UserRepository;
import com.geekonsites.backend.repository.projection.TechnicianAccessView;
import com.geekonsites.backend.service.PasswordResetService;
import com.geekonsites.backend.enums.TechnicianOnboardingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationLoginTest {

    private UserRepository users;
    private PasswordEncoder passwords;
    private JwtService jwt;
    private TechnicianRepository technicians;
    private AuthController authController;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        passwords = mock(PasswordEncoder.class);
        jwt = mock(JwtService.class);
        technicians = mock(TechnicianRepository.class);
        authController = new AuthController(users, passwords, jwt, mock(PasswordResetService.class), technicians);
        when(passwords.matches("correct-password", "encoded-password")).thenReturn(true);
        when(jwt.generateToken(any(User.class))).thenReturn("signed-jwt");
    }

    @Test
    void approvedTechnicianLoginSucceedsUsingLightweightAccessProjection() {
        User technicianUser = user(Role.TECHNICIAN, "tech@example.com");
        TechnicianAccessView access = access("APPROVED");
        when(users.findByEmailIgnoreCase("tech@example.com")).thenReturn(Optional.of(technicianUser));
        when(technicians.findAccessByEmail("tech@example.com")).thenReturn(Optional.of(access));

        LoginResponse response = authController.login(login("tech@example.com"));

        assertEquals(Role.TECHNICIAN, response.getRole());
        assertEquals("signed-jwt", response.getToken());
        verify(technicians).findAccessByEmail("tech@example.com");
        verify(technicians, never()).findByEmail(anyString());
    }

    @Test
    void pendingTechnicianLoginIsRejected() {
        TechnicianAccessView access = access("PENDING");
        when(users.findByEmailIgnoreCase("tech@example.com")).thenReturn(Optional.of(user(Role.TECHNICIAN, "tech@example.com")));
        when(technicians.findAccessByEmail("tech@example.com")).thenReturn(Optional.of(access));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> authController.login(login("tech@example.com")));

        // A pending technician must see a controlled, professional status -
        // never a raw 500 - and the message must not read like a crash.
        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertEquals("Your technician account is awaiting approval.", exception.getReason());
        verify(jwt, never()).generateToken(any());
    }

    @Test
    void rejectedTechnicianLoginIsRejected() {
        TechnicianAccessView access = access("REJECTED");
        when(users.findByEmailIgnoreCase("tech@example.com")).thenReturn(Optional.of(user(Role.TECHNICIAN, "tech@example.com")));
        when(technicians.findAccessByEmail("tech@example.com")).thenReturn(Optional.of(access));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> authController.login(login("tech@example.com")));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertEquals("Your technician account is not approved. Please contact support.", exception.getReason());
        verify(jwt, never()).generateToken(any());
    }

    @Test
    void approvedTechnicianLoginIgnoresLegacyOnboardingState() {
        TechnicianAccessView access = access("APPROVED");
        when(access.getOnboardingStatus()).thenReturn(TechnicianOnboardingStatus.EMAIL_SENT);
        when(users.findByEmailIgnoreCase("tech@example.com")).thenReturn(Optional.of(user(Role.TECHNICIAN, "tech@example.com")));
        when(technicians.findAccessByEmail("tech@example.com")).thenReturn(Optional.of(access));

        LoginResponse response = authController.login(login("tech@example.com"));
        assertEquals(Role.TECHNICIAN, response.getRole());
        assertEquals("signed-jwt", response.getToken());
    }

    @Test
    void unknownEmailReturnsGenericInvalidCredentialsWithoutRevealingWhichPartWasWrong() {
        when(users.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> authController.login(login("nobody@example.com")));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("Invalid email or password.", exception.getReason());
    }

    @Test
    void wrongPasswordReturnsTheSameGenericMessageAsAnUnknownEmail() {
        User customer = user(Role.CUSTOMER, "customer@example.com");
        when(users.findByEmailIgnoreCase("customer@example.com")).thenReturn(Optional.of(customer));
        when(passwords.matches("correct-password", "encoded-password")).thenReturn(false);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> authController.login(login("customer@example.com")));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("Invalid email or password.", exception.getReason());
    }

    @Test
    void customerLoginDoesNotQueryTechnicianTable() {
        when(users.findByEmailIgnoreCase("customer@example.com")).thenReturn(Optional.of(user(Role.CUSTOMER, "customer@example.com")));

        LoginResponse response = authController.login(login("customer@example.com"));

        assertEquals(Role.CUSTOMER, response.getRole());
        verifyNoInteractions(technicians);
    }

    @Test
    void dedicatedAdminLoginRemainsUnaffected() {
        User admin = user(Role.ADMIN, "admin@example.com");
        when(users.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.of(admin));
        AdminAuthController adminController = new AdminAuthController(users, passwords, jwt);

        LoginResponse response = adminController.login(login("admin@example.com"));

        assertEquals(Role.ADMIN, response.getRole());
        assertEquals("signed-jwt", response.getToken());
        verifyNoInteractions(technicians);
    }

    private LoginRequest login(String email) {
        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword("correct-password");
        return request;
    }

    private User user(Role role, String email) {
        User user = new User();
        user.setId(7L);
        user.setFullName("Test User");
        user.setEmail(email);
        user.setPassword("encoded-password");
        user.setPhone("+441234567890");
        user.setCountry("UK");
        user.setRole(role);
        return user;
    }

    private TechnicianAccessView access(String status) {
        TechnicianAccessView view = mock(TechnicianAccessView.class);
        when(view.getVerificationStatus()).thenReturn(status);
        if ("APPROVED".equals(status)) when(view.getOnboardingStatus()).thenReturn(TechnicianOnboardingStatus.PASSWORD_SET);
        return view;
    }
}
