package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.BookingRequest;
import com.geekonsites.backend.dto.CustomerLocationRequest;
import com.geekonsites.backend.dto.TechnicianLocationRequest;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.repository.UserRepository;
import com.geekonsites.backend.service.BookingService;
import com.geekonsites.backend.service.RemoteSessionProvisioningService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.geekonsites.backend.dto.MeetingLinkRequest;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final TechnicianRepository technicianRepository;
    private final UserRepository userRepository;
    private final RemoteSessionProvisioningService remoteSessionProvisioningService;

    public BookingController(
            BookingService bookingService,
            TechnicianRepository technicianRepository,
            UserRepository userRepository,
            RemoteSessionProvisioningService remoteSessionProvisioningService
    ) {
        this.bookingService = bookingService;
        this.technicianRepository = technicianRepository;
        this.userRepository = userRepository;
        this.remoteSessionProvisioningService = remoteSessionProvisioningService;
    }

    @PostMapping
    public ResponseEntity<Booking> createBooking(
            @RequestBody BookingRequest request,
            Authentication authentication
    ) {
        User customer = getLoggedInUser(authentication);

        request.setCustomerId(customer.getId());
        request.setCustomerName(customer.getFullName());
        request.setCustomerEmail(customer.getEmail());
        request.setCustomerPhone(customer.getPhone());

        return ResponseEntity.ok(
                bookingService.createBooking(request)
        );
    }

    @GetMapping
    public ResponseEntity<List<Booking>> getAllBookings() {
        return ResponseEntity.ok(
                bookingService.getAllBookings()
        );
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<Booking> getBookingById(
            @PathVariable Long bookingId,
            Authentication authentication
    ) {
        User user = getLoggedInUser(authentication);
        return ResponseEntity.ok(
                bookingService.getBookingForCurrentUser(bookingId, user)
        );
    }

    @PostMapping("/{bookingId}/remote-session/provision")
    public ResponseEntity<Booking> provisionRemoteSession(
            @PathVariable Long bookingId,
            Authentication authentication
    ) {
        User user = getLoggedInUser(authentication);
        bookingService.getBookingForCurrentUser(bookingId, user);
        return ResponseEntity.ok(
                remoteSessionProvisioningService.provisionAfterPayment(bookingId)
        );
    }

    @GetMapping("/my-bookings")
    public ResponseEntity<List<Booking>> getMyBookings(
            Authentication authentication
    ) {
        User customer = getLoggedInUser(authentication);

        return ResponseEntity.ok(
                bookingService.getBookingsByCustomerId(customer.getId())
        );
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<Booking>> getBookingsByCustomerId(
            @PathVariable Long customerId
    ) {
        return ResponseEntity.ok(
                bookingService.getBookingsByCustomerId(customerId)
        );
    }

    @GetMapping("/technician/{technicianId}")
    public ResponseEntity<List<Booking>> getBookingsByTechnicianId(
            @PathVariable Long technicianId,
            Authentication authentication
    ) {
        // Unlike /customer/{customerId} and /agent/{agentId} (already role-gated
        // to AGENT/ADMIN by SecurityConfig), this route has no request-matcher
        // restricting it to a specific role, so it falls through to the generic
        // "/api/bookings/**" -> authenticated() rule. That let ANY authenticated
        // user - another technician, or a customer - list a different
        // technician's full booking history, including live GPS coordinates.
        // Enforce ownership here: a technician may only fetch their own
        // bookings; agent/admin keep their existing operational access.
        boolean operationalAccess = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_AGENT".equals(authority.getAuthority()) || "ROLE_ADMIN".equals(authority.getAuthority()));
        if (!operationalAccess) {
            boolean isTechnician = authentication != null && authentication.getAuthorities().stream()
                    .anyMatch(authority -> "ROLE_TECHNICIAN".equals(authority.getAuthority()));
            // Check the role before resolving a technician profile: a
            // customer has none, and getLoggedInTechnicianId would otherwise
            // throw a plain RuntimeException here (surfacing as a 500)
            // instead of the intended 403.
            if (!isTechnician || !getLoggedInTechnicianId(authentication).equals(technicianId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot access another technician's bookings");
            }
        }
        return ResponseEntity.ok(
                bookingService.getBookingsByTechnicianId(technicianId)
        );
    }


    @GetMapping("/agent/{agentId}")
    public ResponseEntity<List<Booking>> getBookingsByAgentId(
            @PathVariable Long agentId
    ) {
        return ResponseEntity.ok(
                bookingService.getBookingsByAgentId(agentId)
        );
    }

    @PutMapping("/{bookingId}/assign-technician/{technicianId}")
    public ResponseEntity<Booking> assignTechnician(
            @PathVariable Long bookingId,
            @PathVariable Long technicianId
    ) {
        return ResponseEntity.ok(
                bookingService.assignTechnician(bookingId, technicianId)
        );
    }

    @PutMapping("/{bookingId}/payment-success/{transactionId}")
    public ResponseEntity<Booking> paymentSuccess(
            @PathVariable Long bookingId,
            @PathVariable String transactionId,
            @RequestParam(defaultValue = "CARD") String paymentMethod
    ) {
        return ResponseEntity.ok(
                bookingService.paymentSuccess(
                        bookingId,
                        transactionId,
                        paymentMethod
                )
        );
    }

    // ==========================
    // Technician Workflow APIs
    // ==========================

    @PutMapping("/{bookingId}/technician/accept")
    public ResponseEntity<Booking> technicianAcceptJob(
            @PathVariable Long bookingId,
            Authentication authentication
    ) {
        Long technicianId = getLoggedInTechnicianId(authentication);

        return ResponseEntity.ok(
                bookingService.technicianAcceptJob(
                        bookingId,
                        technicianId
                )
        );
    }

    @PutMapping("/{bookingId}/technician/reject")
    public ResponseEntity<Booking> technicianRejectJob(
            @PathVariable Long bookingId,
            @RequestBody(required = false) Map<String, String> request,
            Authentication authentication
    ) {
        Long technicianId = getLoggedInTechnicianId(authentication);
        String reason = request != null ? request.get("reason") : null;

        return ResponseEntity.ok(
                bookingService.technicianRejectJob(
                        bookingId,
                        technicianId,
                        reason
                )
        );
    }

    @PutMapping("/{bookingId}/technician/on-the-way")
    public ResponseEntity<Booking> technicianOnTheWay(
            @PathVariable Long bookingId,
            Authentication authentication
    ) {
        Long technicianId = getLoggedInTechnicianId(authentication);

        return ResponseEntity.ok(
                bookingService.technicianOnTheWay(
                        bookingId,
                        technicianId
                )
        );
    }

    @PutMapping("/{bookingId}/technician/arrived")
public ResponseEntity<Booking> technicianArrived(
        @PathVariable Long bookingId,
        Authentication authentication
) {
    Long technicianId = getLoggedInTechnicianId(authentication);

    return ResponseEntity.ok(
            bookingService.technicianArrived(
                    bookingId,
                    technicianId
            )
    );
}

    @PutMapping("/{bookingId}/technician/location")
    public ResponseEntity<Booking> updateTechnicianLocation(
            @PathVariable Long bookingId,
            @RequestBody TechnicianLocationRequest request,
            Authentication authentication
    ) {
        Long technicianId = getLoggedInTechnicianId(authentication);

        return ResponseEntity.ok(
                bookingService.updateTechnicianLocation(
                        bookingId,
                        technicianId,
                        request
                )
        );
    }

    @PutMapping("/{bookingId}/technician/start-service")
    public ResponseEntity<Booking> startService(
            @PathVariable Long bookingId,
            Authentication authentication
    ) {
        Long technicianId = getLoggedInTechnicianId(authentication);

        return ResponseEntity.ok(
                bookingService.startService(
                        bookingId,
                        technicianId
                )
        );
    }

    @PutMapping("/{bookingId}/technician/start-remote-session")
    public ResponseEntity<Booking> startRemoteSession(
            @PathVariable Long bookingId,
            @RequestBody Map<String, String> request,
            Authentication authentication
    ) {
        Long technicianId = getLoggedInTechnicianId(authentication);
        String remoteSessionLink = request.get("remoteSessionLink");

        return ResponseEntity.ok(
                bookingService.startRemoteSession(
                        bookingId,
                        technicianId,
                        remoteSessionLink
                )
        );
    }

    @PutMapping("/{bookingId}/technician/complete-service")
    public ResponseEntity<Booking> completeService(
            @PathVariable Long bookingId,
            Authentication authentication
    ) {
        Long technicianId = getLoggedInTechnicianId(authentication);

        return ResponseEntity.ok(
                bookingService.completeService(
                        bookingId,
                        technicianId
                )
        );
    }

    @PutMapping("/{bookingId}/meeting-link")
    public Booking saveMeetingLink(
        @PathVariable Long bookingId,
        @RequestBody MeetingLinkRequest request,
        Authentication authentication
) {
    Long technicianId = getLoggedInTechnicianId(authentication);

    return bookingService.saveMeetingLink(
            bookingId,
            technicianId,
            request.getMeetingLink()
    );
}

    // ==========================
    // Customer / Payment / Invoice
    // ==========================

    @PutMapping("/{bookingId}/customer-location")
    public ResponseEntity<Booking> updateCustomerLocation(
            @PathVariable Long bookingId,
            @RequestBody CustomerLocationRequest request,
            Authentication authentication
    ) {
        User customer = getLoggedInUser(authentication);
        if (customer.getRole() != com.geekonsites.backend.enums.Role.CUSTOMER) {
            throw new RuntimeException("Only the booking customer can update the service location");
        }
        bookingService.getBookingForCurrentUser(bookingId, customer);
        return ResponseEntity.ok(
                bookingService.updateCustomerLocation(
                        bookingId,
                        request
                )
        );
    }

    @PutMapping("/{bookingId}/generate-invoice")
    public ResponseEntity<Booking> generateInvoice(
            @PathVariable Long bookingId,
            Authentication authentication
    ) {
        bookingService.getBookingForCurrentUser(bookingId, getLoggedInUser(authentication));
        return ResponseEntity.ok(
                bookingService.generateInvoice(bookingId)
        );
    }

    @PutMapping("/{bookingId}/remaining-payment-success/{transactionId}")
    public ResponseEntity<Booking> remainingPaymentSuccess(
            @PathVariable Long bookingId,
            @PathVariable String transactionId,
            @RequestParam(defaultValue = "CARD") String paymentMethod
    ) {
        return ResponseEntity.ok(
                bookingService.remainingPaymentSuccess(
                        bookingId,
                        transactionId,
                        paymentMethod
                )
        );
    }

    @PutMapping("/{bookingId}/rating")
    public ResponseEntity<Booking> rateBooking(
            @PathVariable Long bookingId,
            @RequestBody Map<String, String> request,
            Authentication authentication
    ) {
        User customer = getLoggedInUser(authentication);

        Integer rating = Integer.parseInt(request.get("rating"));
        String review = request.get("review");

        return ResponseEntity.ok(
                bookingService.rateBooking(
                        bookingId,
                        customer.getId(),
                        rating,
                        review
                )
        );
    }

    @PutMapping("/{bookingId}/close")
    public ResponseEntity<Booking> closeBooking(
            @PathVariable Long bookingId
    ) {
        return ResponseEntity.ok(
                bookingService.closeBooking(bookingId)
        );
    }

    @PutMapping("/{bookingId}/status/{status}")
    public ResponseEntity<Booking> updateStatus(
            @PathVariable Long bookingId,
            @PathVariable BookingStatus status
    ) {
        return ResponseEntity.ok(
                bookingService.updateStatus(
                        bookingId,
                        status
                )
        );
    }

    @GetMapping("/{bookingId}/tracking")
    public ResponseEntity<Booking> getTracking(
            @PathVariable Long bookingId,
            Authentication authentication
    ) {
        User user = getLoggedInUser(authentication);
        return ResponseEntity.ok(
                bookingService.getBookingForCurrentUser(bookingId, user)
        );
    }

    private User getLoggedInUser(Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            throw new RuntimeException("Unauthorized user");
        }

        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    private Long getLoggedInTechnicianId(Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            throw new RuntimeException("Unauthorized technician");
        }

        return technicianRepository.findAccessByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Technician profile not found"))
                .getId();
    }

    // Expected business-validation failures (e.g. BookingService.assignTechnician
    // rejecting a technician not approved for the booking's service mode) are
    // signalled as ResponseStatusException so they carry the right 4xx status.
    // Without this handler, Spring's default error page only includes the
    // reason in the JSON body when server.error.include-message is enabled,
    // so callers (including the Agent Assign Technician UI) could still see
    // an unhelpful/empty response. This makes the reason explicit for every
    // /api/bookings endpoint without touching validation logic or status codes.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleBookingValidationError(ResponseStatusException exception) {
        String message = exception.getReason() != null ? exception.getReason() : "Request could not be completed";
        String code = "Selected service is not available for the chosen support method.".equals(message)
                ? "SERVICE_MODE_NOT_SUPPORTED"
                : "BOOKING_VALIDATION_FAILED";
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of(
                "status", exception.getStatusCode().value(),
                "code", code,
                "message", message
        ));
    }
}
