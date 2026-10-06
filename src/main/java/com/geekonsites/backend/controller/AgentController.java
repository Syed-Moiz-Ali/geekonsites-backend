package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.AgentDashboardStats;
import com.geekonsites.backend.dto.AgentRequest;
import com.geekonsites.backend.entity.Agent;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Notification;
import com.geekonsites.backend.repository.AgentRepository;
import com.geekonsites.backend.service.AgentService;
import com.geekonsites.backend.service.NotificationService;
import com.geekonsites.backend.service.AgentOperationsService;
import com.geekonsites.backend.dto.AgentOperationsDtos.DashboardSummary;
import com.geekonsites.backend.repository.projection.AgentBookingQueueView;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;
    private final AgentRepository agentRepository;
    private final NotificationService notificationService;
    private final AgentOperationsService agentOperationsService;

    public AgentController(
            AgentService agentService,
            AgentRepository agentRepository,
            NotificationService notificationService,
            AgentOperationsService agentOperationsService
    ) {
        this.agentService = agentService;
        this.agentRepository = agentRepository;
        this.notificationService = notificationService;
        this.agentOperationsService = agentOperationsService;
    }

    @PostMapping
    public ResponseEntity<Agent> createAgent(@RequestBody AgentRequest request) {
        return ResponseEntity.ok(agentService.createAgent(request));
    }

    @GetMapping
    public ResponseEntity<List<Agent>> getAllAgents() {
        return ResponseEntity.ok(agentService.getAllAgents());
    }

    @GetMapping("/my-notifications")
    public ResponseEntity<List<Notification>> getMyNotifications(
            Authentication authentication
    ) {
        Long agentId = getLoggedInAgentId(authentication);

        return ResponseEntity.ok(
                notificationService.getAgentNotifications(agentId)
        );
    }

    @GetMapping("/me")
    public ResponseEntity<Agent> getMyProfile(Authentication authentication) {
        return ResponseEntity.ok(agentService.getAgentById(getLoggedInAgentId(authentication)));
    }

    @GetMapping("/dashboard-summary")
    public ResponseEntity<DashboardSummary> getDashboardSummary(Authentication authentication) {
        return ResponseEntity.ok(agentOperationsService.summary(authentication));
    }

    @GetMapping("/booking-queue")
    public ResponseEntity<Page<AgentBookingQueueView>> getBookingQueue(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(agentOperationsService.bookingQueue(page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Agent> getAgentById(
            @PathVariable("id") Long id,
            Authentication authentication
    ) {
        authorizeAgentScope(authentication, id);
        return ResponseEntity.ok(agentService.getAgentById(id));
    }

    @GetMapping("/{agentId}/bookings")
    public ResponseEntity<List<Booking>> getAgentBookings(
            @PathVariable("agentId") Long agentId,
            Authentication authentication
    ) {
        authorizeAgentScope(authentication, agentId);
        return ResponseEntity.ok(agentService.getAgentBookings(agentId));
    }

    @GetMapping("/{agentId}/dashboard-stats")
    public ResponseEntity<AgentDashboardStats> getDashboardStats(
            @PathVariable("agentId") Long agentId,
            Authentication authentication
    ) {
        authorizeAgentScope(authentication, agentId);
        return ResponseEntity.ok(agentService.getDashboardStats(agentId));
    }

    @GetMapping("/unassigned-bookings")
    public ResponseEntity<List<Booking>> getUnassignedBookings() {
        return ResponseEntity.ok(agentService.getUnassignedBookings());
    }

    @PutMapping("/{agentId}/assign-booking/{bookingId}")
    public ResponseEntity<Booking> assignBookingToAgent(
            @PathVariable("agentId") Long agentId,
            @PathVariable("bookingId") Long bookingId,
            Authentication authentication
    ) {
        authorizeAgentScope(authentication, agentId);
        return ResponseEntity.ok(
                agentService.assignBookingToAgent(bookingId, agentId)
        );
    }

    private Long getLoggedInAgentId(Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            throw new RuntimeException("Unauthorized agent");
        }

        Agent agent = agentRepository
                .findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Agent profile not found"));

        return agent.getId();
    }

    private void authorizeAgentScope(Authentication authentication, Long requestedAgentId) {
        boolean admin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (admin) return;
        if (!getLoggedInAgentId(authentication).equals(requestedAgentId)) {
            throw new RuntimeException("You cannot access another agent's workspace");
        }
    }
}
