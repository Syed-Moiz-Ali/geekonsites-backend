package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.repository.BookingRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class RemoteSessionService {

    private final BookingRepository bookingRepository;

    public RemoteSessionService(
            BookingRepository bookingRepository
    ) {
        this.bookingRepository = bookingRepository;
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

        booking.setBookingStatus(BookingStatus.REMOTE_SESSION_STARTED);
        booking.setRemoteSessionStartedAt(LocalDateTime.now());

        if (!isValidGoogleMeetLink(booking.getRemoteSessionLink())) {
            throw new RuntimeException("A valid Google Meet link must be saved before starting a remote session");
        }

        return bookingRepository.save(booking);
    }

    public Booking endRemoteSession(Long bookingId, Long technicianId) {

        Booking booking = getTechnicianBooking(bookingId, technicianId);

        booking.setRemoteSessionEndedAt(LocalDateTime.now());
        booking.setBookingStatus(BookingStatus.SERVICE_COMPLETED);

        return bookingRepository.save(booking);
    }

    private Booking getBooking(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() ->
                        new RuntimeException("Booking not found"));
    }

    private Booking getTechnicianBooking(Long bookingId, Long technicianId) {
        Booking booking = getBooking(bookingId);
        if (booking.getTechnicianId() == null || !booking.getTechnicianId().equals(technicianId)) {
            throw new RuntimeException("You are not assigned to this remote session");
        }
        return booking;
    }

    private void requirePaidRemoteBooking(Booking booking) {
        if (booking.getServiceMode() == null || !"REMOTE".equals(booking.getServiceMode().name())) {
            throw new RuntimeException("This booking is not a remote service");
        }
        if (!"PAID".equalsIgnoreCase(booking.getPaymentStatus())) {
            throw new RuntimeException("Full payment is required before remote session access");
        }
    }

    private boolean isValidGoogleMeetLink(String meetingLink) {
        return meetingLink != null && meetingLink.trim().matches(
                "^https://meet\\.google\\.com/[a-zA-Z]{3}-[a-zA-Z]{4}-[a-zA-Z]{3}(?:[?#].*)?$"
        );
    }
}
