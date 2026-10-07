package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;

import java.time.LocalDateTime;

/**
 * PHASE 10 (local-e2e) — the boundary for creating a remote-support meeting.
 *
 * <p>Production uses the real Google Calendar implementation
 * ({@link GoogleCalendarService}); the {@code local-e2e} profile substitutes a
 * deterministic local provider. Only the external provider boundary moves — all
 * lifecycle/persistence logic stays in {@link RemoteSessionProvisioningService}.
 */
public interface RemoteMeetingProvider {

    boolean isConfigured();

    String configurationIssue();

    MeetingDetails createGoogleMeetLink(Booking booking);

    void syncExistingEventAttendees(Booking booking);

    record MeetingDetails(String eventId, String meetingLink, LocalDateTime scheduledStart, LocalDateTime scheduledEnd) {
    }
}
