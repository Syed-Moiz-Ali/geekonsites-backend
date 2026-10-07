package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.NotificationRequest;
import com.geekonsites.backend.dto.PageRequestParams;
import com.geekonsites.backend.dto.PageResponse;
import com.geekonsites.backend.entity.Notification;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.NotificationType;
import com.geekonsites.backend.repository.AgentRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.service.NotificationService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final TechnicianRepository technicianRepository;
    private final AgentRepository agentRepository;

    public NotificationController(
            NotificationService notificationService,
            TechnicianRepository technicianRepository,
            AgentRepository agentRepository
    ) {
        this.notificationService = notificationService;
        this.technicianRepository = technicianRepository;
        this.agentRepository = agentRepository;
    }

    /**
     * PHASE 9 — paginated, owned notification inbox. A caller can only ever read their own
     * notifications (recipient id is derived from the authenticated principal). Optional
     * {@code read} filter; page size is capped by {@link PageRequestParams}.
     */
    @GetMapping("/my-notifications")
    public ResponseEntity<PageResponse<Notification>> getMyNotifications(
            @RequestParam(required = false) Boolean read,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        User user = authenticatedUser(authentication);
        Pageable pageable = PageRequestParams.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        PageResponse<Notification> response = switch (user.getRole()) {
            case TECHNICIAN -> notificationService.listTechnicianNotifications(recipientId(user), read, pageable);
            case AGENT -> notificationService.listAgentNotifications(recipientId(user), read, pageable);
            case ADMIN -> notificationService.listAdminNotifications(user.getId(), pageable);
            case CUSTOMER -> notificationService.listCustomerNotifications(user.getId(), read, pageable);
        };
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{notificationId}/read")
    public ResponseEntity<Notification> markAsRead(
            @PathVariable Long notificationId,
            Authentication authentication) {
        User user = authenticatedUser(authentication);
        return ResponseEntity.ok(switch (user.getRole()) {
            case TECHNICIAN -> notificationService.markTechnicianNotificationRead(recipientId(user), notificationId);
            case AGENT -> notificationService.markAgentNotificationRead(recipientId(user), notificationId);
            case ADMIN -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Notifications are not available for this role");
            case CUSTOMER -> notificationService.markCustomerNotificationRead(user.getId(), notificationId);
        });
    }

    @PutMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead(Authentication authentication) {
        User user = authenticatedUser(authentication);
        switch (user.getRole()) {
            case TECHNICIAN -> notificationService.markAllTechnicianNotificationsRead(recipientId(user));
            case AGENT -> notificationService.markAllAgentNotificationsRead(recipientId(user));
            case ADMIN -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Notifications are not available for this role");
            case CUSTOMER -> notificationService.markAllCustomerNotificationsRead(user.getId());
        }
        return ResponseEntity.noContent().build();
    }

    private User authenticatedUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated account is required");
        }
        return user;
    }

    private Long recipientId(User user) {
        return switch (user.getRole()) {
            case TECHNICIAN -> technicianRepository.findAccessByEmail(user.getEmail())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Technician profile not found")).getId();
            case AGENT -> agentRepository.findByEmail(user.getEmail())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent profile not found")).getId();
            default -> user.getId();
        };
    }

    // ============================================ operational (AGENT/ADMIN) compatibility

    /** @deprecated operational helper; use the paginated inbox. Kept as a thin delegate. */
    @Deprecated
    @PostMapping
    public ResponseEntity<Notification> createNotification(@RequestBody NotificationRequest request) {
        if (request.getCustomerId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "customerId is required");
        }
        notificationService.createNotification(
                request.getCustomerId(), NotificationType.BOOKING_UPDATE, request.getTitle(), request.getMessage());
        List<Notification> latest = notificationService.getCustomerNotifications(request.getCustomerId());
        return ResponseEntity.ok(latest.isEmpty() ? null : latest.get(0));
    }

    /** @deprecated use {@code GET /api/notifications/my-notifications}. */
    @Deprecated
    @GetMapping("/{customerId}")
    public ResponseEntity<PageResponse<Notification>> getNotifications(
            @PathVariable("customerId") Long customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(notificationService.listCustomerNotifications(
                customerId, null, PageRequestParams.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }
}
