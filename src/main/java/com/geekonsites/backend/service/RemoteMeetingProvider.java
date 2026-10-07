package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;

import java.time.LocalDateTime;

/**
 * Boundary for creating a remote-support meeting. The production implementation is the
 * Google Calendar one ({@link GoogleCalendarService}); all lifecycle/persistence logic
 * stays in {@link RemoteSessionProvisioningService}.
 */
public interface RemoteMeetingProvider {

    boolean isConfigured();

    String configurationIssue();

    MeetingDetails createGoogleMeetLink(Booking booking);

    void syncExistingEventAttendees(Booking booking);

    record MeetingDetails(String eventId, String meetingLink, LocalDateTime scheduledStart, LocalDateTime scheduledEnd) {
    }
}
