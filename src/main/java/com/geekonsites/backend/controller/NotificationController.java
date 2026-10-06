package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.NotificationRequest;
import com.geekonsites.backend.entity.Notification;
import com.geekonsites.backend.repository.NotificationRepository;
import com.geekonsites.backend.service.NotificationService;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.repository.AgentRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final TechnicianRepository technicianRepository;
    private final AgentRepository agentRepository;

    public NotificationController(
            NotificationRepository notificationRepository,
            NotificationService notificationService,
            TechnicianRepository technicianRepository,
            AgentRepository agentRepository
    ) {
        this.notificationRepository = notificationRepository;
        this.notificationService = notificationService;
        this.technicianRepository = technicianRepository;
        this.agentRepository = agentRepository;
    }

    @GetMapping("/my-notifications")
    public ResponseEntity<List<Notification>> getMyNotifications(Authentication authentication) {
        User user = authenticatedUser(authentication);
        return ResponseEntity.ok(switch (user.getRole()) {
            case TECHNICIAN -> notificationService.getTechnicianNotifications(recipientId(user));
            case AGENT -> notificationService.getAgentNotifications(recipientId(user));
            default -> notificationService.getCustomerNotifications(user.getId());
        });
    }

    @PutMapping("/{notificationId}/read")
    public ResponseEntity<Notification> markAsRead(
            @PathVariable Long notificationId,
            Authentication authentication) {
        User user = authenticatedUser(authentication);
        return ResponseEntity.ok(switch (user.getRole()) {
            case TECHNICIAN -> notificationService.markTechnicianNotificationRead(recipientId(user), notificationId);
            case AGENT -> notificationService.markAgentNotificationRead(recipientId(user), notificationId);
            default -> notificationService.markCustomerNotificationRead(user.getId(), notificationId);
        });
    }

    @PutMapping("/read-all")
    public ResponseEntity<List<Notification>> markAllAsRead(Authentication authentication) {
        User user = authenticatedUser(authentication);
        return ResponseEntity.ok(switch (user.getRole()) {
            case TECHNICIAN -> notificationService.markAllTechnicianNotificationsRead(recipientId(user));
            case AGENT -> notificationService.markAllAgentNotificationsRead(recipientId(user));
            default -> notificationService.markAllCustomerNotificationsRead(user.getId());
        });
    }

    private User authenticatedUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            throw new IllegalArgumentException("Authenticated account is required");
        }
        if (!"CUSTOMER".equalsIgnoreCase(String.valueOf(user.getRole())) &&
                !"TECHNICIAN".equalsIgnoreCase(String.valueOf(user.getRole())) &&
                !"AGENT".equalsIgnoreCase(String.valueOf(user.getRole()))) {
            throw new IllegalArgumentException("Notifications are not available for this role");
        }
        return user;
    }

    private Long recipientId(User user) {
        return switch (user.getRole()) {
            case TECHNICIAN -> technicianRepository.findAccessByEmail(user.getEmail())
                    .orElseThrow(() -> new IllegalArgumentException("Technician profile not found")).getId();
            case AGENT -> agentRepository.findByEmail(user.getEmail())
                    .orElseThrow(() -> new IllegalArgumentException("Agent profile not found")).getId();
            default -> user.getId();
        };
    }

    @PostMapping
    public ResponseEntity<Notification> createNotification(
            @RequestBody NotificationRequest request
    ) {

        Notification notification =
                new Notification();

        notification.setCustomerId(
                request.getCustomerId()
        );

        notification.setTitle(
                request.getTitle()
        );

        notification.setMessage(
                request.getMessage()
        );

        notification.setIsRead(false);

        notification.setCreatedAt(
                LocalDateTime.now()
        );

        return ResponseEntity.ok(
                notificationRepository.save(
                        notification
                )
        );
    }

    @GetMapping("/{customerId}")
    public ResponseEntity<List<Notification>>
    getNotifications(
            @PathVariable("customerId")
            Long customerId
    ) {

        return ResponseEntity.ok(
                notificationRepository
                        .findByCustomerIdOrderByCreatedAtDesc(
                                customerId
                        )
        );
    }
}
