package com.geekonsites.backend.config;

import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email:}")
    private String configuredEmail;

    @Value("${app.admin.password:}")
    private String configuredPassword;

    @Override
    public void run(ApplicationArguments args) {
        String email = configuredEmail == null ? "" : configuredEmail.trim().toLowerCase();
        String password = configuredPassword == null ? "" : configuredPassword;

        if (email.isBlank() && password.isBlank()) {
            return;
        }
        if (email.isBlank() || password.length() < 12) {
            throw new IllegalStateException("ADMIN_EMAIL and an ADMIN_PASSWORD of at least 12 characters are required together");
        }

        userRepository.findByEmailIgnoreCase(email).ifPresentOrElse(existing -> {
            if (existing.getRole() != Role.ADMIN) {
                throw new IllegalStateException("ADMIN_EMAIL belongs to a non-admin account");
            }
            if (!passwordEncoder.matches(password, existing.getPassword())) {
                existing.setPassword(passwordEncoder.encode(password));
                userRepository.save(existing);
            }
        }, () -> {
            User admin = new User();
            admin.setFullName("GeekOnSites Administrator");
            admin.setEmail(email);
            admin.setPassword(passwordEncoder.encode(password));
            admin.setCountry("US");
            admin.setRole(Role.ADMIN);
            userRepository.save(admin);
        });
    }
}
