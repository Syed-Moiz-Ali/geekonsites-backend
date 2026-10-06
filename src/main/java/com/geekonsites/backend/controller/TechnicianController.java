package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.TechnicianRequest;
import com.geekonsites.backend.dto.TechnicianRegistrationResponse;
import com.geekonsites.backend.dto.TechnicianAdminResponse;
import com.geekonsites.backend.dto.TechnicianSetPasswordRequest;
import com.geekonsites.backend.dto.TechnicianSetPasswordResponse;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Notification;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.service.BookingService;
import com.geekonsites.backend.service.NotificationService;
import com.geekonsites.backend.service.TechnicianService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Base64;

@RestController
@RequestMapping("/api/technicians")
public class TechnicianController {

    private static final Logger log = LoggerFactory.getLogger(TechnicianController.class);

    private final TechnicianService technicianService;
    private final TechnicianRepository technicianRepository;
    private final BookingService bookingService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public TechnicianController(
            TechnicianService technicianService,
            TechnicianRepository technicianRepository,
            BookingService bookingService,
            NotificationService notificationService,
            ObjectMapper objectMapper
    ) {
        this.technicianService = technicianService;
        this.technicianRepository = technicianRepository;
        this.bookingService = bookingService;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<?> createTechnician(
            @RequestBody TechnicianRequest request,
            HttpServletRequest httpRequest
    ) {
        long startedAt = System.nanoTime();
        long requestBytes = httpRequest.getContentLengthLong();
        try {
            TechnicianRegistrationResponse response = technicianService.createTechnician(request);
            log.info("Technician registration endpoint performance requestBytes={} responseBytes={} totalMs={}",
                    requestBytes, estimateResponseBytes(response), elapsedMillis(startedAt));
            return ResponseEntity.ok(response);
        } catch (RuntimeException exception) {
            log.warn("Technician registration failed requestBytes={} totalMs={} reasonType={}",
                    requestBytes, elapsedMillis(startedAt), exception.getClass().getSimpleName());
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    private long estimateResponseBytes(TechnicianRegistrationResponse response) {
        try {
            return objectMapper.writeValueAsBytes(response).length;
        } catch (JsonProcessingException exception) {
            return -1L;
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    @GetMapping
    public ResponseEntity<List<Technician>> getAllTechnicians() {
        return ResponseEntity.ok(
                technicianService.getAllTechnicians()
        );
    }

    @GetMapping("/pending")
    public ResponseEntity<List<Technician>> getPendingTechnicians() {
        return ResponseEntity.ok(
                technicianService.getPendingTechnicians()
        );
    }

    @GetMapping("/my-bookings")
    public ResponseEntity<List<Booking>> getMyTechnicianBookings(
            Authentication authentication
    ) {
        Long technicianId = getLoggedInTechnicianId(authentication);

        return ResponseEntity.ok(
                bookingService.getBookingsByTechnicianId(technicianId)
        );
    }

    @GetMapping("/my-notifications")
    public ResponseEntity<List<Notification>> getMyNotifications(
            Authentication authentication
    ) {
        Long technicianId = getLoggedInTechnicianId(authentication);

        return ResponseEntity.ok(
                notificationService.getTechnicianNotifications(technicianId)
        );
    }

    @GetMapping("/me")
    public ResponseEntity<Technician> getMyProfile(Authentication authentication) {
        return ResponseEntity.ok(technicianService.getTechnicianById(getLoggedInTechnicianId(authentication)));
    }

    @GetMapping("/me/photo")
    public ResponseEntity<byte[]> getMyProfilePhoto(Authentication authentication) {
        Technician technician = technicianService.getTechnicianById(getLoggedInTechnicianId(authentication));
        return evidenceResponse(technician.getLivePhotoData());
    }

    @PutMapping("/me/availability")
    public ResponseEntity<Technician> updateMyAvailability(
            @RequestBody Map<String, String> request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(technicianService.updateAvailability(
                getLoggedInTechnicianId(authentication), request.get("status")));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Technician> getTechnicianById(
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(
                technicianService.getTechnicianById(id)
        );
    }

    @GetMapping("/{id}/verification/{kind}")
    public ResponseEntity<byte[]> getVerificationEvidence(
            @PathVariable Long id,
            @PathVariable String kind
    ) {
        Technician technician = technicianService.getTechnicianById(id);
        String dataUrl = switch (kind) {
            case "live-photo" -> technician.getLivePhotoData();
            case "work-authorization" -> technician.getWorkAuthorizationDocumentData();
            case "address-proof" -> technician.getAddressProofData();
            case "driving-license" -> technician.getDrivingLicenseData();
            case "vehicle-insurance" -> technician.getVehicleInsuranceData();
            case "public-liability" -> technician.getPublicLiabilityData();
            default -> technician.getIdentityDocumentData();
        };
        return evidenceResponse(dataUrl);
    }

    @PutMapping("/{id}/approve")
    public ResponseEntity<TechnicianAdminResponse> approveTechnician(
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(
                technicianService.approveTechnician(id)
        );
    }

    @PutMapping("/{id}/reject")
    public ResponseEntity<Technician> rejectTechnician(
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(
                technicianService.rejectTechnician(id)
        );
    }

    private Long getLoggedInTechnicianId(Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            throw new RuntimeException("Unauthorized technician");
        }

        return technicianRepository.findAccessByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Technician profile not found"))
                .getId();
    }

    @PostMapping("/{id}/resend-onboarding")
    public ResponseEntity<TechnicianAdminResponse> resendOnboarding(@PathVariable Long id) {
        return ResponseEntity.ok(technicianService.resendOnboarding(id));
    }

    @PostMapping("/onboarding/set-password")
    public ResponseEntity<TechnicianSetPasswordResponse> setOnboardingPassword(
            @RequestBody TechnicianSetPasswordRequest request
    ) {
        return ResponseEntity.ok(technicianService.setOnboardingPassword(request));
    }

    private ResponseEntity<byte[]> evidenceResponse(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:") || !dataUrl.contains(",") || !dataUrl.contains(";")) {
            return ResponseEntity.notFound().build();
        }
        try {
            int comma = dataUrl.indexOf(',');
            String mediaType = dataUrl.substring(5, dataUrl.indexOf(';'));
            byte[] content = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mediaType))
                    .header("Cache-Control", "private, no-store")
                    .body(content);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.unprocessableEntity().build();
        }
    }
}
