package com.geekonsites.backend.controller;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.service.RemoteSessionService;
import com.geekonsites.backend.repository.TechnicianRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/remote-sessions")
public class RemoteSessionController {

    private final RemoteSessionService remoteSessionService;
    private final TechnicianRepository technicianRepository;

    public RemoteSessionController(
            RemoteSessionService remoteSessionService,
            TechnicianRepository technicianRepository
    ) {
        this.remoteSessionService = remoteSessionService;
        this.technicianRepository = technicianRepository;
    }

    @PostMapping("/booking/{bookingId}/create")
    public ResponseEntity<Booking> createRemoteSession(
            @PathVariable("bookingId") Long bookingId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                remoteSessionService.createRemoteSession(bookingId, getTechnicianId(authentication))
        );
    }

    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<Booking> getRemoteSession(
            @PathVariable("bookingId") Long bookingId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                remoteSessionService.getRemoteSession(bookingId, getTechnicianId(authentication))
        );
    }

    @PutMapping("/booking/{bookingId}/start")
    public ResponseEntity<Booking> startRemoteSession(
            @PathVariable("bookingId") Long bookingId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                remoteSessionService.startRemoteSession(bookingId, getTechnicianId(authentication))
        );
    }

    @PutMapping("/booking/{bookingId}/end")
    public ResponseEntity<Booking> endRemoteSession(
            @PathVariable("bookingId") Long bookingId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                remoteSessionService.endRemoteSession(bookingId, getTechnicianId(authentication))
        );
    }

    private Long getTechnicianId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new RuntimeException("Authentication required");
        }
        return technicianRepository.findAccessByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Technician profile not found"))
                .getId();
    }
}
