package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.RatingRequest;
import com.geekonsites.backend.dto.TechnicianRatingResponse;
import com.geekonsites.backend.entity.Rating;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.repository.RatingRepository;
import com.geekonsites.backend.service.RatingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * PHASE 5 — rating submission delegates to the single {@link RatingService} authority.
 */
@RestController
@RequestMapping("/api/ratings")
public class RatingController {

    private final RatingService ratingService;
    private final RatingRepository ratingRepository;

    public RatingController(RatingService ratingService, RatingRepository ratingRepository) {
        this.ratingService = ratingService;
        this.ratingRepository = ratingRepository;
    }

    @PostMapping
    public ResponseEntity<Rating> submitRating(
            @Valid @RequestBody RatingRequest request,
            Authentication authentication
    ) {
        User customer = authenticatedUser(authentication);
        return ResponseEntity.ok(ratingService.submitRating(
                request.getBookingId(), request.getRating(), request.getReview(), customer));
    }

    // PHASE 7: validation and domain failures are rendered by the central GlobalExceptionHandler.

    @GetMapping("/technician/{technicianId}")
    public ResponseEntity<TechnicianRatingResponse> getTechnicianRating(
            @PathVariable("technicianId") Long technicianId
    ) {
        List<Rating> ratings = ratingRepository.findByTechnicianId(technicianId);

        TechnicianRatingResponse response = new TechnicianRatingResponse();
        response.setTechnicianId(technicianId);
        response.setTotalReviews(ratings.size());
        response.setAverageRating(
                ratings.stream().mapToInt(Rating::getRating).average().orElse(0));
        return ResponseEntity.ok(response);
    }

    private User authenticatedUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return user;
    }

}
