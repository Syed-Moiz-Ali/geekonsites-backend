package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.AdminDashboardStats;
import com.geekonsites.backend.entity.Notification;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.repository.UserRepository;
import com.geekonsites.backend.service.AdminService;
import com.geekonsites.backend.service.NotificationService;
import com.geekonsites.backend.service.RemoteSessionProvisioningService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.geekonsites.backend.enums.Role;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final RemoteSessionProvisioningService remoteSessionProvisioningService;

    public AdminController(
            AdminService adminService,
            NotificationService notificationService,
            UserRepository userRepository,
            RemoteSessionProvisioningService remoteSessionProvisioningService
    ) {
        this.adminService = adminService;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.remoteSessionProvisioningService = remoteSessionProvisioningService;
    }

    @GetMapping("/dashboard-stats")
    public ResponseEntity<AdminDashboardStats> getDashboardStats() {
        return ResponseEntity.ok(
                adminService.getDashboardStats()
        );
    }

    @GetMapping("/customers")
    public ResponseEntity<com.geekonsites.backend.dto.PageResponse<com.geekonsites.backend.dto.AdminCustomerResponse>> getCustomers(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(adminService.getCustomers(search,
                com.geekonsites.backend.dto.PageRequestParams.of(page, size,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id"))));
    }

    @GetMapping("/remote-sessions")
    public ResponseEntity<List<Booking>> getRemoteSessions() {
        return ResponseEntity.ok(adminService.getRemoteSessions());
    }

    @PostMapping("/remote-sessions/{bookingId}/provision")
    public ResponseEntity<Booking> provisionRemoteSession(@PathVariable Long bookingId) {
        return ResponseEntity.ok(remoteSessionProvisioningService.provisionAfterPayment(bookingId));
    }

    @GetMapping("/my-notifications")
    public ResponseEntity<List<Notification>> getMyNotifications(
            Authentication authentication
    ) {
        Long adminId = getLoggedInAdminId(authentication);

        return ResponseEntity.ok(
                notificationService.getAdminNotifications(adminId)
        );
    }

    private Long getLoggedInAdminId(Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            throw new RuntimeException("Unauthorized admin");
        }

        User admin = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Admin user not found"));

        if (admin.getRole() == null ||
                !"ADMIN".equalsIgnoreCase(admin.getRole().name())) {
            throw new RuntimeException("User is not an admin");
        }

        return admin.getId();
    }
}
