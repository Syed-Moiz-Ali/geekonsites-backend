package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.RatingRequest;
import com.geekonsites.backend.dto.TechnicianRatingResponse;
import com.geekonsites.backend.entity.Rating;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.repository.RatingRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.service.BookingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ratings")
public class RatingController {

    private final RatingRepository ratingRepository;
    private final BookingService bookingService;
    private final TechnicianRepository technicianRepository;

    public RatingController(RatingRepository ratingRepository, BookingService bookingService, TechnicianRepository technicianRepository) {
        this.ratingRepository = ratingRepository;
        this.bookingService = bookingService;
        this.technicianRepository = technicianRepository;
    }

    @PostMapping
    public ResponseEntity<Rating> submitRating(
            @RequestBody RatingRequest request,
            Authentication authentication
    ) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User customer)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        Booking booking = bookingService.getBookingForCurrentUser(request.getBookingId(), customer);
        bookingService.rateBooking(request.getBookingId(), customer.getId(), request.getRating(), request.getReview());

        Rating rating = new Rating();

        rating.setBookingId(request.getBookingId());
        rating.setCustomerId(customer.getId());
        rating.setTechnicianId(booking.getTechnicianId());
        rating.setRating(request.getRating());
        rating.setReview(request.getReview());

        Rating saved = ratingRepository.save(rating);

        // Keep Technician.rating (the aggregate shown on the technician's own
        // profile) in sync with real submitted ratings, using the same
        // average this controller already computes on demand below - it was
        // previously only ever set once at registration and never updated.
        if (booking.getTechnicianId() != null) {
            technicianRepository.findById(booking.getTechnicianId()).ifPresent(technician -> {
                double average = ratingRepository.findByTechnicianId(booking.getTechnicianId()).stream()
                        .mapToInt(Rating::getRating)
                        .average()
                        .orElse(0);
                technician.setRating(average);
                technicianRepository.save(technician);
            });
        }

        return ResponseEntity.ok(saved);
    }
     
     @GetMapping("/technician/{technicianId}")
        public ResponseEntity<TechnicianRatingResponse>
        getTechnicianRating(
        @PathVariable("technicianId") Long technicianId
    ){

        List<Rating> ratings =
                ratingRepository.findByTechnicianId(
                        technicianId
                );

        TechnicianRatingResponse response =
                new TechnicianRatingResponse();

        response.setTechnicianId(
                technicianId
        );

        response.setTotalReviews(
                ratings.size()
        );

        double average =
                ratings.stream()
                        .mapToInt(Rating::getRating)
                        .average()
                        .orElse(0);

        response.setAverageRating(
                average
        );

        return ResponseEntity.ok(
                response
        );
    }

    // Mirrors BookingController's handler: an expected business-rule
    // rejection (wrong owner, wrong booking state, invalid rating value)
    // must reach the caller as a real 4xx with a message, not an empty
    // response or a generic 500.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleRatingValidationError(ResponseStatusException exception) {
        String message = exception.getReason() != null ? exception.getReason() : "Request could not be completed";
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", message));
    }
}
