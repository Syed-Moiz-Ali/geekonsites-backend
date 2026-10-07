package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Rating;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.RatingRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.Set;

/**
 * PHASE 5 — the single authoritative rating/review submission path.
 *
 * <p>Rules enforced here:
 * <ul>
 *   <li>only an authenticated CUSTOMER who owns the booking may submit;</li>
 *   <li>the booking must have genuinely completed service;</li>
 *   <li>one review per booking, enforced by application check AND a DB unique constraint
 *       (concurrency-safe; a losing race becomes 409);</li>
 *   <li>the technician is derived from the booking, never from the request;</li>
 *   <li>the technician aggregate is recalculated from stored ratings.</li>
 * </ul>
 */
@Service
@Transactional
public class RatingService {

    private static final Set<BookingStatus> REVIEWABLE = EnumSet.of(
            BookingStatus.SERVICE_COMPLETED,
            BookingStatus.REMAINING_PAYMENT_PENDING,
            BookingStatus.FULLY_PAID,
            BookingStatus.INVOICE_GENERATED,
            BookingStatus.BOOKING_CLOSED
    );

    private final RatingRepository ratingRepository;
    private final BookingRepository bookingRepository;
    private final TechnicianRepository technicianRepository;

    public RatingService(
            RatingRepository ratingRepository,
            BookingRepository bookingRepository,
            TechnicianRepository technicianRepository
    ) {
        this.ratingRepository = ratingRepository;
        this.bookingRepository = bookingRepository;
        this.technicianRepository = technicianRepository;
    }

    public Rating submitRating(Long bookingId, Integer ratingValue, String review, User customer) {
        if (customer == null || customer.getRole() != Role.CUSTOMER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a customer can submit a review");
        }
        if (bookingId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A booking is required");
        }

        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));

        if (!customer.getId().equals(booking.getCustomerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not allowed to review this booking");
        }
        if (booking.getBookingStatus() == null || !REVIEWABLE.contains(booking.getBookingStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A booking can be rated only after service completion");
        }
        if (ratingValue == null || ratingValue < 1 || ratingValue > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rating must be between 1 and 5");
        }
        if (ratingRepository.existsByBookingId(bookingId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking has already been reviewed");
        }

        Rating rating = new Rating();
        rating.setBookingId(bookingId);
        rating.setCustomerId(customer.getId());
        // Technician derived from the authoritative booking relationship.
        rating.setTechnicianId(booking.getTechnicianId());
        rating.setRating(ratingValue);
        rating.setReview(review);

        try {
            rating = ratingRepository.saveAndFlush(rating);
        } catch (DataIntegrityViolationException duplicate) {
            // Concurrent submission won the unique index: no duplicate row created.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking has already been reviewed");
        }

        recalculateTechnicianAggregate(booking.getTechnicianId());
        return rating;
    }

    private void recalculateTechnicianAggregate(Long technicianId) {
        if (technicianId == null) {
            return;
        }
        technicianRepository.findById(technicianId).ifPresent(technician -> {
            double average = ratingRepository.findByTechnicianId(technicianId).stream()
                    .mapToInt(Rating::getRating)
                    .average()
                    .orElse(0);
            technician.setRating(average);
            technicianRepository.save(technician);
        });
    }

    public static boolean isReviewable(Booking booking) {
        return booking != null && booking.getBookingStatus() != null
                && REVIEWABLE.contains(booking.getBookingStatus());
    }
}
