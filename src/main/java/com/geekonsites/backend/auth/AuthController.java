package com.geekonsites.backend.auth;

import com.geekonsites.backend.dto.AuthResponse;
import com.geekonsites.backend.dto.LoginRequest;
import com.geekonsites.backend.dto.LoginResponse;
import com.geekonsites.backend.dto.RegisterRequest;
import com.geekonsites.backend.dto.ForgotPasswordRequest;
import com.geekonsites.backend.dto.ResetPasswordRequest;
import com.geekonsites.backend.dto.ChangePasswordRequest;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.jwt.JwtService;
import com.geekonsites.backend.repository.UserRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import java.util.Map;
import com.geekonsites.backend.service.PasswordResetService;
import com.geekonsites.backend.enums.Role;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final PasswordResetService passwordResetService;
    private final TechnicianRepository technicianRepository;

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, String>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }

        User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user was not found."));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Current password is incorrect."));
        }
        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("message", "New password must be different from the current password."));
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Password changed successfully."));
    }

    @PostMapping("/forgot-password")
    public Map<String, String> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request);
        return Map.of("message", "If an account exists for that email, reset instructions have been sent.");
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        try {
            passwordResetService.resetPassword(request);
            return ResponseEntity.ok(Map.of("message", "Password updated successfully."));
        } catch (PasswordResetService.InvalidResetTokenException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {

        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new RuntimeException("Email already registered");
        }

        String country = "UK".equalsIgnoreCase(request.getCountry()) ? "UK" : "US";

        User user = new User();
        user.setFullName(request.getFullName());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setPhone(request.getPhone());
        user.setCountry(country);
        // Public registration must never be able to grant privileged roles.
        user.setRole(Role.CUSTOMER);

        User savedUser = userRepository.save(user);

        return new AuthResponse(
                savedUser.getId(),
                savedUser.getFullName(),
                savedUser.getEmail(),
                savedUser.getPhone(),
                savedUser.getCountry(),
                savedUser.getRole(),
                "Registration successful"
        );
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {

        // Unknown email and wrong password must be indistinguishable to the
        // caller (same status, same message) so a login attempt can never be
        // used to discover whether a given email has an account.
        User user = userRepository.findByEmailIgnoreCase(request.getEmail().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password."));

        if (user.getRole() == Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Use the dedicated admin portal to sign in.");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }

        if (user.getRole() == com.geekonsites.backend.enums.Role.TECHNICIAN) {
            var technician = technicianRepository.findAccessByEmail(user.getEmail())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password."));
            String status = technician.getVerificationStatus();
            if ("PENDING".equalsIgnoreCase(status)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your technician account is awaiting approval.");
            }
            if (!"APPROVED".equalsIgnoreCase(status)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your technician account is not approved. Please contact support.");
            }
        }

        String token = jwtService.generateToken(user);

        return new LoginResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getPhone(),
                user.getCountry(),
                user.getRole(),
                token,
                "Login successful"
        );
    }
}
