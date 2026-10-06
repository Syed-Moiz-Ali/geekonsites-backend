package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.repository.BookingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RemoteSessionProvisioningService {
    private final BookingRepository bookingRepository;
    private final GoogleCalendarService googleCalendarService;
    private final NotificationService notificationService;

    public RemoteSessionProvisioningService(
            BookingRepository bookingRepository,
            GoogleCalendarService googleCalendarService,
            NotificationService notificationService
    ) {
        this.bookingRepository = bookingRepository;
        this.googleCalendarService = googleCalendarService;
        this.notificationService = notificationService;
    }

    @Transactional
    public Booking provisionAfterPayment(Long bookingId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        if (!isRemote(booking)) throw new RuntimeException("Remote session provisioning is available only for remote bookings");
        if (!"PAID".equalsIgnoreCase(booking.getPaymentStatus())) {
            booking.setRemoteSessionStatus("PAYMENT_PENDING");
            booking.setRemoteSessionLink(null);
            booking.setGoogleCalendarEventId(null);
            bookingRepository.save(booking);
            throw new RuntimeException("Full payment is required before remote session provisioning");
        }
        if (hasText(booking.getRemoteSessionLink()) && hasText(booking.getGoogleCalendarEventId())) {
            return syncExistingEventAttendees(booking);
        }

        booking.setRemoteSessionRequired(true);
        booking.setRemoteSessionStatus("PROVISIONING");
        booking.setRemoteSessionProvisioningError(null);
        bookingRepository.save(booking);

        if (!googleCalendarService.isConfigured()) {
            booking.setRemoteSessionStatus("FAILED");
            booking.setRemoteSessionProvisioningError(googleCalendarService.configurationIssue());
            return bookingRepository.save(booking);
        }

        try {
            GoogleCalendarService.MeetingDetails meeting = googleCalendarService.createGoogleMeetLink(booking);
            booking.setGoogleCalendarEventId(meeting.eventId());
            booking.setRemoteSessionLink(meeting.meetingLink());
            booking.setRemoteSessionScheduledStart(meeting.scheduledStart());
            booking.setRemoteSessionScheduledEnd(meeting.scheduledEnd());
            booking.setRemoteSessionStatus("READY");
            booking.setRemoteSessionProvisioningError(null);
            Booking saved = bookingRepository.save(booking);
            notificationService.createNotification(
                    saved.getCustomerId(),
                    "Remote Session Ready",
                    "Your secure Google Meet session for booking GOS-" + saved.getId() + " is ready."
            );
            return saved;
        } catch (RuntimeException error) {
            booking.setRemoteSessionStatus("FAILED");
            booking.setRemoteSessionProvisioningError(limit(error.getMessage()));
            return bookingRepository.save(booking);
        }
    }

    public Booking syncAssignedParticipantsBeforeStart(Booking booking) {
        if (!isRemote(booking) || booking.getRemoteSessionStartedAt() != null || booking.getServiceCompletedAt() != null) return booking;
        if (!hasText(booking.getRemoteSessionLink()) || !hasText(booking.getGoogleCalendarEventId())) return booking;
        return syncExistingEventAttendees(booking);
    }

    private Booking syncExistingEventAttendees(Booking booking) {
        if (!googleCalendarService.isConfigured()) return booking;
        try {
            googleCalendarService.syncExistingEventAttendees(booking);
            booking.setRemoteSessionProvisioningError(null);
        } catch (RuntimeException error) {
            booking.setRemoteSessionProvisioningError("Calendar attendee update failed");
        }
        return bookingRepository.save(booking);
    }

    private boolean isRemote(Booking booking) {
        return Boolean.TRUE.equals(booking.getRemoteSessionRequired()) || booking.getServiceMode() == ServiceMode.REMOTE;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String limit(String value) {
        if (value == null) return "Unknown Google Calendar error";
        return value.length() > 1800 ? value.substring(0, 1800) : value;
    }
}
