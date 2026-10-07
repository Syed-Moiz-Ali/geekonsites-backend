package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.BookingRequest;
import com.geekonsites.backend.dto.CustomerLocationRequest;
import com.geekonsites.backend.dto.TechnicianLocationRequest;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.repository.UserRepository;
import com.geekonsites.backend.service.BookingService;
import com.geekonsites.backend.service.RatingService;
import com.geekonsites.backend.service.RemoteSessionProvisioningService;
import jakarta.validation.Valid;
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
    private final RatingService ratingService;
    private final TechnicianRepository technicianRepository;
    private final UserRepository userRepository;
    private final RemoteSessionProvisioningService remoteSessionProvisioningService;

    public BookingController(
            BookingService bookingService,
            RatingService ratingService,
            TechnicianRepository technicianRepository,
            UserRepository userRepository,
            RemoteSessionProvisioningService remoteSessionProvisioningService
    ) {
        this.bookingService = bookingService;
        this.ratingService = ratingService;
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

        // PHASE 5: the normal customer booking endpoint is CUSTOMER-only. Operational
        // accounts must never silently become a booking's customer. (Defense in depth;
        // SecurityConfig also requires ROLE_CUSTOMER.)
        if (customer.getRole() != Role.CUSTOMER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a customer account can create a booking");
        }

        // Customer identity always comes from the authenticated principal.
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

    /** PHASE 9 — paginated operational booking list (AGENT/ADMIN). */
    @GetMapping("/page")
    public ResponseEntity<com.geekonsites.backend.dto.PageResponse<Booking>> getAllBookingsPaged(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(bookingService.getAllBookings(
                com.geekonsites.backend.dto.PageRequestParams.of(page, size,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))));
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

    /** PHASE 9 — paginated customer booking history; ownership from the principal. */
    @GetMapping("/my-bookings/page")
    public ResponseEntity<com.geekonsites.backend.dto.PageResponse<Booking>> getMyBookingsPaged(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        User customer = getLoggedInUser(authentication);
        return ResponseEntity.ok(bookingService.getBookingsByCustomerId(customer.getId(),
                com.geekonsites.backend.dto.PageRequestParams.of(page, size,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))));
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

    // NOTE (PHASE 1): the legacy admin-only "/payment-success/{transactionId}" endpoint
    // was removed. It accepted a client-supplied transaction id and marked a booking
    // paid without any Stripe verification (audit C2 / BUG-02). Payment state can now
    // only be changed by the verified Stripe webhook or the authenticated
    // confirm-checkout-session fallback in PaymentService.

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
            @Valid @RequestBody TechnicianLocationRequest request,
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
            @Valid @RequestBody CustomerLocationRequest request,
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
        // PHASE 5: authorize BEFORE any mutation. Technicians are rejected here.
        bookingService.authorizeInvoiceAction(bookingId, getLoggedInUser(authentication));
        return ResponseEntity.ok(
                bookingService.generateInvoice(bookingId)
        );
    }

    // NOTE (PHASE 1): the legacy admin-only "/remaining-payment-success/{transactionId}"
    // endpoint was removed for the same reason as "/payment-success" (audit C2 / BUG-02).

    @PutMapping("/{bookingId}/rating")
    public ResponseEntity<Booking> rateBooking(
            @PathVariable Long bookingId,
            @RequestBody Map<String, String> request,
            Authentication authentication
    ) {
        User customer = getLoggedInUser(authentication);

        String ratingText = request.get("rating");
        Integer rating = ratingText == null || ratingText.isBlank() ? null : Integer.valueOf(ratingText);
        String review = request.get("review");

        // PHASE 5: single rating authority (ownership, lifecycle, one-per-booking).
        ratingService.submitRating(bookingId, rating, review, customer);
        return ResponseEntity.ok(bookingService.getBookingById(bookingId));
    }

    @PutMapping("/{bookingId}/close")
    public ResponseEntity<Booking> closeBooking(
            @PathVariable Long bookingId
    ) {
        return ResponseEntity.ok(
                bookingService.closeBooking(bookingId)
        );
    }

    // NOTE (PHASE 2): the arbitrary "PUT /{bookingId}/status/{status}" endpoint was
    // removed. It let AGENT/ADMIN select any BookingStatus directly. All lifecycle
    // changes now happen through named business actions enforced by BookingStateMachine.

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

    // PHASE 7: business failures are rendered by the central GlobalExceptionHandler using
    // the standard ApiErrorResponse contract (the former local {status,code,message} handler
    // was removed for a single consistent error shape).
}
