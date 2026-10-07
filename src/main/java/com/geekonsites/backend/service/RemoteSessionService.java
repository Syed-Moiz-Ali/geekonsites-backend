package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.repository.BookingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * PHASE 2 — remote-session operations.
 *
 * <p>This service keeps only remote-session-specific responsibilities (creating/reading
 * the session marker, validating the meeting link). It no longer implements its own
 * booking-state rules: starting and ending a remote session delegate to
 * {@link BookingService}, which routes lifecycle transitions through
 * {@link BookingStateMachine}. This removes the weaker parallel remote lifecycle path.
 */
@Service
public class RemoteSessionService {

    private final BookingRepository bookingRepository;
    private final BookingService bookingService;

    public RemoteSessionService(
            BookingRepository bookingRepository,
            BookingService bookingService
    ) {
        this.bookingRepository = bookingRepository;
        this.bookingService = bookingService;
    }

    public Booking createRemoteSession(Long bookingId, Long technicianId) {

        Booking booking = getTechnicianBooking(bookingId, technicianId);

        requirePaidRemoteBooking(booking);

        booking.setRemoteSessionRequired(true);

        return bookingRepository.save(booking);
    }

    public Booking getRemoteSession(Long bookingId, Long technicianId) {
        Booking booking = getTechnicianBooking(bookingId, technicianId);
        requirePaidRemoteBooking(booking);
        return booking;
    }

    public Booking startRemoteSession(Long bookingId, Long technicianId) {

        Booking booking = getTechnicianBooking(bookingId, technicianId);

        requirePaidRemoteBooking(booking);

        if (!isValidGoogleMeetLink(booking.getRemoteSessionLink())) {
            throw new InvalidBookingTransitionException(HttpStatus.BAD_REQUEST,
                    "A valid Google Meet link must be saved before starting a remote session");
        }

        return bookingService.startRemoteSession(bookingId, technicianId, booking.getRemoteSessionLink());
    }

    public Booking endRemoteSession(Long bookingId, Long technicianId) {

        Booking booking = getTechnicianBooking(bookingId, technicianId);

        if (booking.getServiceMode() != ServiceMode.REMOTE) {
            throw new InvalidBookingTransitionException(HttpStatus.BAD_REQUEST,
                    "This booking is not a remote service");
        }

        return bookingService.completeService(bookingId, technicianId);
    }

    private Booking getBooking(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() ->
                        new InvalidBookingTransitionException(HttpStatus.NOT_FOUND, "Booking not found"));
    }

    private Booking getTechnicianBooking(Long bookingId, Long technicianId) {
        Booking booking = getBooking(bookingId);
        if (booking.getTechnicianId() == null || !booking.getTechnicianId().equals(technicianId)) {
            throw new InvalidBookingTransitionException(HttpStatus.FORBIDDEN,
                    "You are not assigned to this remote session");
        }
        return booking;
    }

    private void requirePaidRemoteBooking(Booking booking) {
        if (booking.getServiceMode() != ServiceMode.REMOTE) {
            throw new InvalidBookingTransitionException(HttpStatus.BAD_REQUEST,
                    "This booking is not a remote service");
        }
        if (!"PAID".equalsIgnoreCase(booking.getPaymentStatus())) {
            throw new InvalidBookingTransitionException(HttpStatus.CONFLICT,
                    "Full payment is required before remote session access");
        }
    }

    private boolean isValidGoogleMeetLink(String meetingLink) {
        return meetingLink != null && meetingLink.trim().matches(
                "^https://meet\\.google\\.com/[a-zA-Z]{3}-[a-zA-Z]{4}-[a-zA-Z]{3}(?:[?#].*)?$"
        );
    }
}
