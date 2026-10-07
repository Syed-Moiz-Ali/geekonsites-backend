package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.ConferenceData;
import com.google.api.services.calendar.model.ConferenceSolutionKey;
import com.google.api.services.calendar.model.CreateConferenceRequest;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GoogleCalendarService implements RemoteMeetingProvider {
    private static final String APPLICATION_NAME = "GeekOnSites Remote Support";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*(AM|PM)?", Pattern.CASE_INSENSITIVE);

    @Value("${google.calendar.enabled:false}")
    private boolean enabled;
    @Value("${google.calendar.oauth-client-json:}")
    private String googleOauthClientJson;
    @Value("${google.calendar.refresh-token:}")
    private String refreshToken;
    @Value("${google.calendar.id:primary}")
    private String calendarId;
    @Value("${google.calendar.time-zone:UTC}")
    private String timeZone;
    private final TechnicianRepository technicianRepository;

    public GoogleCalendarService(TechnicianRepository technicianRepository) {
        this.technicianRepository = technicianRepository;
    }

    public boolean isConfigured() {
        return enabled && hasText(googleOauthClientJson) && hasText(refreshToken);
    }

    public String configurationIssue() {
        if (!enabled) return "Google Calendar provisioning is disabled. Set GOOGLE_CALENDAR_ENABLED=true.";
        if (!hasText(googleOauthClientJson)) return "Missing GOOGLE_CALENDAR_OAUTH_CLIENT_JSON.";
        if (!hasText(refreshToken)) return "Missing GOOGLE_CALENDAR_REFRESH_TOKEN.";
        return "Google Calendar configuration is ready.";
    }

    public MeetingDetails createGoogleMeetLink(Booking booking) {
        if (!isConfigured()) throw new IllegalStateException("Google Calendar is not configured");

        try {
            // Use the JVM truststore configured by the runtime. GoogleNetHttpTransport
            // ships its own CA set and rejects valid locally inspected TLS chains.
            NetHttpTransport transport = new NetHttpTransport();
            Calendar calendar = calendarClient(transport);
            ZoneId zone = ZoneId.of(timeZone);
            LocalDateTime start = resolveStart(booking);
            LocalDateTime end = start.plusHours(1);

            String eventId = "gosbooking" + booking.getId();
            Event event = new Event()
                    .setId(eventId)
                    .setSummary("GeekOnSites Remote Support - GOS-" + booking.getId())
                    .setDescription("GeekOnSites remote support session.\n\nCustomer: " + safe(booking.getCustomerName())
                            + "\nService: " + safe(booking.getServiceType())
                            + "\nIssue: " + safe(booking.getIssueDescription()))
                    .setStart(eventTime(start, zone))
                    .setEnd(eventTime(end, zone))
                    .setConferenceData(new ConferenceData().setCreateRequest(
                            new CreateConferenceRequest()
                                    .setRequestId("gos-meet-booking-" + booking.getId())
                                    .setConferenceSolutionKey(new ConferenceSolutionKey().setType("hangoutsMeet"))));

            event.setAttendees(eventAttendees(booking));

            Event created;
            try {
                created = calendar.events().insert(calendarId, event)
                        .setConferenceDataVersion(1)
                        .setSendUpdates("all")
                        .execute();
            } catch (GoogleJsonResponseException conflict) {
                if (conflict.getStatusCode() != 409) throw conflict;
                created = calendar.events().get(calendarId, eventId).execute();
                created = updateAttendeesIfChanged(calendar, created, eventAttendees(booking));
            }
            String meetingLink = meetingLink(created);
            if (!hasText(meetingLink)) {
                throw new IllegalStateException("Google Calendar did not return a Meet link");
            }
            return new MeetingDetails(created.getId(), meetingLink, start, end);
        } catch (Exception error) {
            throw new RuntimeException("Failed to create Google Meet session: " + error.getMessage(), error);
        }
    }

    public void syncExistingEventAttendees(Booking booking) {
        if (!isConfigured()) throw new IllegalStateException("Google Calendar is not configured");
        if (!hasText(booking.getGoogleCalendarEventId())) throw new IllegalArgumentException("Calendar event is not available");
        try {
            NetHttpTransport transport = new NetHttpTransport();
            Calendar calendar = calendarClient(transport);
            Event existing = calendar.events().get(calendarId, booking.getGoogleCalendarEventId()).execute();
            Event updated = updateAttendeesIfChanged(calendar, existing, eventAttendees(booking));
            String existingMeet = meetingLink(updated);
            if (hasText(booking.getRemoteSessionLink()) && hasText(existingMeet)
                    && !booking.getRemoteSessionLink().equals(existingMeet)) {
                throw new IllegalStateException("Existing Calendar event returned a different Meet link");
            }
        } catch (Exception error) {
            throw new RuntimeException("Failed to update Google Calendar attendees", error);
        }
    }

    List<String> attendeeEmails(Booking booking) {
        Map<String, String> unique = new LinkedHashMap<>();
        addEmail(unique, booking.getCustomerEmail());
        if (booking.getTechnicianId() != null) {
            technicianRepository.findById(booking.getTechnicianId())
                    .map(technician -> technician.getPersonalEmail())
                    .filter(this::hasText)
                    .ifPresent(email -> addEmail(unique, email));
        }
        return new ArrayList<>(unique.values());
    }

    private List<EventAttendee> eventAttendees(Booking booking) {
        return attendeeEmails(booking).stream().map(email -> new EventAttendee().setEmail(email)).toList();
    }

    private void addEmail(Map<String, String> unique, String email) {
        if (!hasText(email)) return;
        String trimmed = email.trim();
        unique.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
    }

    private Event updateAttendeesIfChanged(Calendar calendar, Event event, List<EventAttendee> desired) throws Exception {
        List<String> currentEmails = event.getAttendees() == null ? List.of() : event.getAttendees().stream()
                .map(EventAttendee::getEmail).filter(this::hasText)
                .map(email -> email.trim().toLowerCase(Locale.ROOT)).distinct().sorted().toList();
        List<String> desiredEmails = desired.stream().map(EventAttendee::getEmail)
                .map(email -> email.trim().toLowerCase(Locale.ROOT)).distinct().sorted().toList();
        if (currentEmails.equals(desiredEmails)) return event;
        event.setAttendees(desired);
        return calendar.events().update(calendarId, event.getId(), event)
                .setConferenceDataVersion(1)
                .setSendUpdates("all")
                .execute();
    }

    private Calendar calendarClient(NetHttpTransport transport) throws Exception {
        return new Calendar.Builder(transport, JSON_FACTORY, getCredentials(transport))
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    private Credential getCredentials(NetHttpTransport transport) throws Exception {
        GoogleClientSecrets secrets = GoogleClientSecrets.load(JSON_FACTORY, new StringReader(googleOauthClientJson));
        GoogleCredential credential = new GoogleCredential.Builder()
                .setTransport(transport)
                .setJsonFactory(JSON_FACTORY)
                .setClientSecrets(secrets)
                .build()
                .setRefreshToken(refreshToken.trim());
        if (!credential.refreshToken()) {
            throw new IllegalStateException("Google Calendar refresh token is invalid or expired");
        }
        return credential;
    }

    private EventDateTime eventTime(LocalDateTime value, ZoneId zone) {
        return new EventDateTime()
                .setDateTime(new com.google.api.client.util.DateTime(java.util.Date.from(value.atZone(zone).toInstant())))
                .setTimeZone(zone.getId());
    }

    private LocalDateTime resolveStart(Booking booking) {
        LocalDate date = booking.getBookingDate() != null ? booking.getBookingDate() : LocalDate.now().plusDays(1);
        LocalDateTime requested = LocalDateTime.of(date, parseTime(booking.getTimeSlot()));
        return requested.isAfter(LocalDateTime.now().plusMinutes(4)) ? requested : LocalDateTime.now().plusMinutes(5);
    }

    private LocalTime parseTime(String timeSlot) {
        if (!hasText(timeSlot)) return LocalTime.of(10, 0);
        Matcher matcher = TIME_PATTERN.matcher(timeSlot.trim().toUpperCase(Locale.ROOT));
        if (!matcher.find()) return LocalTime.of(10, 0);
        int hour = Integer.parseInt(matcher.group(1));
        int minute = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        String meridiem = matcher.group(3);
        if (meridiem != null) {
            hour %= 12;
            if ("PM".equalsIgnoreCase(meridiem)) hour += 12;
        }
        return LocalTime.of(Math.min(hour, 23), Math.min(minute, 59));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String meetingLink(Event event) {
        if (hasText(event.getHangoutLink())) return event.getHangoutLink();
        if (event.getConferenceData() == null || event.getConferenceData().getEntryPoints() == null) return null;
        return event.getConferenceData().getEntryPoints().stream()
                .filter(point -> "video".equalsIgnoreCase(point.getEntryPointType()))
                .map(com.google.api.services.calendar.model.EntryPoint::getUri)
                .filter(this::hasText)
                .findFirst().orElse(null);
    }

    private String safe(String value) {
        return hasText(value) ? value : "Not provided";
    }
}
