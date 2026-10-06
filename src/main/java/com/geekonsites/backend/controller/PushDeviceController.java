package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.PushDeviceTokenRequest;
import com.geekonsites.backend.entity.PushDeviceToken;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.repository.AgentRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.service.PushNotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications/devices")
@RequiredArgsConstructor
public class PushDeviceController {
    private final PushNotificationService pushNotificationService;
    private final TechnicianRepository technicianRepository;
    private final AgentRepository agentRepository;

    @PostMapping
    public ResponseEntity<PushDeviceToken> register(
            @Valid @RequestBody PushDeviceTokenRequest request,
            Authentication authentication) {
        User user = authenticatedUser(authentication);
        String role = String.valueOf(user.getRole());
        return ResponseEntity.ok(pushNotificationService.register(resolveRecipientId(user, role), role, request.getToken(), request.getPlatform()));
    }

    @DeleteMapping
    public ResponseEntity<Void> unregister(
            @Valid @RequestBody PushDeviceTokenRequest request,
            Authentication authentication) {
        User user = authenticatedUser(authentication);
        String role = String.valueOf(user.getRole());
        pushNotificationService.unregister(resolveRecipientId(user, role), role, request.getToken());
        return ResponseEntity.noContent().build();
    }

    private User authenticatedUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            throw new IllegalArgumentException("Authenticated account is required");
        }
        return user;
    }

    // Notifications are created and looked up against the role-specific profile id
    // (Technician.id / Agent.id), which is NOT guaranteed to equal the login User.id
    // (they're separate tables — an agent's user id and agent id can differ). Push
    // devices must be registered under that same id or delivery would silently miss.
    private Long resolveRecipientId(User user, String role) {
        if ("TECHNICIAN".equalsIgnoreCase(role)) {
            return technicianRepository.findAccessByEmail(user.getEmail()).map(technician -> technician.getId()).orElse(user.getId());
        }
        if ("AGENT".equalsIgnoreCase(role)) {
            return agentRepository.findByEmail(user.getEmail()).map(agent -> agent.getId()).orElse(user.getId());
        }
        return user.getId();
    }
}
