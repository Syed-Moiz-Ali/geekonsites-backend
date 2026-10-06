package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.ForgotPasswordRequest;
import com.geekonsites.backend.dto.ResetPasswordRequest;
import com.geekonsites.backend.entity.PasswordResetToken;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.repository.PasswordResetTokenRepository;
import com.geekonsites.backend.repository.UserRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.enums.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class PasswordResetService {
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(30);
    private static final Duration RESEND_COOLDOWN = Duration.ofMinutes(1);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Pattern RAW_TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final TechnicianRepository technicianRepository;

    @Value("${app.password-reset.allowed-origins}")
    private String allowedOrigins;

    @Transactional
    public void requestReset(ForgotPasswordRequest request) {
        userRepository.findByEmailIgnoreCase(request.email().trim()).ifPresent(user -> {
            if (user.getRole() == Role.TECHNICIAN) {
                var technician = technicianRepository.findAccessByEmail(user.getEmail());
                if (technician.isEmpty() || !"APPROVED".equalsIgnoreCase(technician.get().getVerificationStatus())) return;
            }
            if (tokenRepository.findFirstByUserOrderByCreatedAtDesc(user)
                    .filter(existing -> !existing.isUsed())
                    .filter(existing -> existing.getExpiresAt().isAfter(Instant.now()))
                    .filter(existing -> existing.getCreatedAt().isAfter(Instant.now().minus(RESEND_COOLDOWN)))
                    .isPresent()) return;
            byte[] bytes = new byte[32];
            SECURE_RANDOM.nextBytes(bytes);
            String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

            PasswordResetToken resetToken = new PasswordResetToken();
            resetToken.setUser(user);
            resetToken.setTokenHash(hash(rawToken));
            resetToken.setExpiresAt(Instant.now().plus(TOKEN_LIFETIME));
            tokenRepository.saveAndFlush(resetToken);

            String resetUrl = safeResetUrl(request.resetUrl());
            String roleHint = user.getRole() == Role.TECHNICIAN ? "&role=technician" : "";
            String email = user.getEmail();
            String link = resetUrl + "?token=" + rawToken + roleHint;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emailService.sendPasswordResetEmail(email, link);
                }
            });
        });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        if (!RAW_TOKEN_PATTERN.matcher(request.token()).matches()) {
            throw new InvalidResetTokenException();
        }
        PasswordResetToken token = tokenRepository.findByTokenHashAndUsedFalse(hash(request.token()))
                .orElseThrow(InvalidResetTokenException::new);
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidResetTokenException();
        }
        User user = token.getUser();
        user.setPassword(passwordEncoder.encode(request.password()));
        userRepository.save(user);
        token.setUsed(true);
        tokenRepository.save(token);
    }

    private String safeResetUrl(String requestedUrl) {
        String fallback = "https://geekonsites.com/reset-password";
        if (requestedUrl == null || requestedUrl.isBlank()) return fallback;
        Set<String> origins = Stream.of(allowedOrigins.split(",")).map(String::trim).collect(Collectors.toSet());
        return origins.stream().anyMatch(origin -> requestedUrl.equals(origin + "/reset-password")) ? requestedUrl : fallback;
    }

    private String hash(String token) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static final class InvalidResetTokenException extends RuntimeException {
        public InvalidResetTokenException() {
            super("Reset link is invalid, expired, or already used.");
        }
    }
}
