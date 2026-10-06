package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.repository.BookingRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteSessionProvisioningServiceTest {

    @Test
    void paidRemoteBookingCreatesAndPersistsOneMeet() {
        Fixture fixture = fixture(paidBooking());
        when(fixture.google.isConfigured()).thenReturn(true);
        when(fixture.google.createGoogleMeetLink(any())).thenReturn(new GoogleCalendarService.MeetingDetails(
                "gosbooking1", "https://meet.google.com/abc-defg-hij", LocalDateTime.now(), LocalDateTime.now().plusHours(1)));

        Booking result = fixture.service.provisionAfterPayment(1L);

        assertEquals("READY", result.getRemoteSessionStatus());
        assertEquals("https://meet.google.com/abc-defg-hij", result.getRemoteSessionLink());
        verify(fixture.google, times(1)).createGoogleMeetLink(result);
    }

    @Test
    void existingMeetIsReusedAndRetryDoesNotCreateDuplicate() {
        Booking booking = paidBooking();
        booking.setGoogleCalendarEventId("gosbooking1");
        booking.setRemoteSessionLink("https://meet.google.com/abc-defg-hij");
        booking.setRemoteSessionStatus("READY");
        Fixture fixture = fixture(booking);
        when(fixture.google.isConfigured()).thenReturn(true);

        assertSame(booking, fixture.service.provisionAfterPayment(1L));
        assertSame(booking, fixture.service.provisionAfterPayment(1L));
        assertEquals("gosbooking1", booking.getGoogleCalendarEventId());
        assertEquals("https://meet.google.com/abc-defg-hij", booking.getRemoteSessionLink());
        verify(fixture.google, times(2)).syncExistingEventAttendees(booking);
        verify(fixture.google, never()).createGoogleMeetLink(any());
    }

    @Test
    void reassignmentBeforeStartSynchronizesExistingEventWithoutCreatingMeet() {
        Booking booking = paidBooking();
        booking.setTechnicianId(22L);
        booking.setGoogleCalendarEventId("gosbooking1");
        booking.setRemoteSessionLink("https://meet.google.com/abc-defg-hij");
        Fixture fixture = fixture(booking);
        when(fixture.google.isConfigured()).thenReturn(true);

        Booking result = fixture.service.syncAssignedParticipantsBeforeStart(booking);

        assertSame(booking, result);
        verify(fixture.google).syncExistingEventAttendees(booking);
        verify(fixture.google, never()).createGoogleMeetLink(any());
        assertEquals("https://meet.google.com/abc-defg-hij", result.getRemoteSessionLink());
    }

    @Test
    void activeSessionIsNotChangedByReassignmentSync() {
        Booking booking = paidBooking();
        booking.setGoogleCalendarEventId("gosbooking1");
        booking.setRemoteSessionLink("https://meet.google.com/abc-defg-hij");
        booking.setRemoteSessionStartedAt(LocalDateTime.now());
        Fixture fixture = fixture(booking);

        assertSame(booking, fixture.service.syncAssignedParticipantsBeforeStart(booking));
        verifyNoInteractions(fixture.google);
    }

    @Test
    void unpaidBookingCannotProvisionMeet() {
        Booking booking = paidBooking();
        booking.setPaymentStatus("PENDING");
        Fixture fixture = fixture(booking);
        assertThrows(RuntimeException.class, () -> fixture.service.provisionAfterPayment(1L));
        verifyNoInteractions(fixture.google);
        assertNull(booking.getRemoteSessionLink());
    }

    @Test
    void googleFailureStoresControlledFailedStateWithoutFabricatedUrl() {
        Booking booking = paidBooking();
        Fixture fixture = fixture(booking);
        when(fixture.google.isConfigured()).thenReturn(true);
        when(fixture.google.createGoogleMeetLink(booking)).thenThrow(new RuntimeException("Google unavailable"));

        Booking result = fixture.service.provisionAfterPayment(1L);

        assertEquals("FAILED", result.getRemoteSessionStatus());
        assertNull(result.getRemoteSessionLink());
        assertTrue(result.getRemoteSessionProvisioningError().contains("Google unavailable"));
    }

    @Test
    void missingConfigurationReportsRequiredEnvironmentVariable() {
        Booking booking = paidBooking();
        Fixture fixture = fixture(booking);
        when(fixture.google.isConfigured()).thenReturn(false);
        when(fixture.google.configurationIssue()).thenReturn("Missing GOOGLE_CALENDAR_OAUTH_CLIENT_JSON.");
        Booking result = fixture.service.provisionAfterPayment(1L);
        assertEquals("FAILED", result.getRemoteSessionStatus());
        assertEquals("Missing GOOGLE_CALENDAR_OAUTH_CLIENT_JSON.", result.getRemoteSessionProvisioningError());
    }

    private Fixture fixture(Booking booking) {
        BookingRepository repository = mock(BookingRepository.class);
        GoogleCalendarService google = mock(GoogleCalendarService.class);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(booking));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        return new Fixture(new RemoteSessionProvisioningService(repository, google, mock(NotificationService.class)), google);
    }

    private Booking paidBooking() {
        Booking booking = new Booking();
        booking.setId(1L);
        booking.setCustomerId(7L);
        booking.setServiceMode(ServiceMode.REMOTE);
        booking.setRemoteSessionRequired(true);
        booking.setPaymentStatus("PAID");
        return booking;
    }

    private record Fixture(RemoteSessionProvisioningService service, GoogleCalendarService google) {}
}
