package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteSessionPaymentGateTest {

    @Test
    void unpaidAndFailedRemoteBookingsCannotCreateOrAccessSession() {
        BookingRepository repository = mock(BookingRepository.class);
        RemoteSessionService service = new RemoteSessionService(repository);
        Booking booking = remote("PENDING", 5L);
        when(repository.findById(1L)).thenReturn(Optional.of(booking));

        assertThrows(RuntimeException.class, () -> service.createRemoteSession(1L, 5L));
        booking.setPaymentStatus("FAILED");
        assertThrows(RuntimeException.class, () -> service.getRemoteSession(1L, 5L));
        verify(repository, never()).save(any());
    }

    @Test
    void paidRemoteBookingCanProgress() {
        BookingRepository repository = mock(BookingRepository.class);
        RemoteSessionService service = new RemoteSessionService(repository);
        Booking booking = remote("PAID", 5L);
        when(repository.findById(1L)).thenReturn(Optional.of(booking));
        when(repository.save(booking)).thenReturn(booking);

        assertSame(booking, service.createRemoteSession(1L, 5L));
        verify(repository).save(booking);
    }

    @Test
    void anotherTechnicianCannotAccessRemoteBooking() {
        BookingRepository repository = mock(BookingRepository.class);
        RemoteSessionService service = new RemoteSessionService(repository);
        when(repository.findById(1L)).thenReturn(Optional.of(remote("PAID", 5L)));
        assertThrows(RuntimeException.class, () -> service.getRemoteSession(1L, 9L));
    }

    @Test
    void onsiteBookingIsUnaffectedByRemoteWorkflowAndCannotBeConverted() {
        BookingRepository repository = mock(BookingRepository.class);
        RemoteSessionService service = new RemoteSessionService(repository);
        Booking booking = remote("PARTIALLY_PAID", 5L);
        booking.setServiceMode(ServiceMode.ONSITE);
        when(repository.findById(1L)).thenReturn(Optional.of(booking));
        assertThrows(RuntimeException.class, () -> service.createRemoteSession(1L, 5L));
        assertEquals("PARTIALLY_PAID", booking.getPaymentStatus());
    }

    @Test
    void unpaidBookingNeverSerializesStoredMeetingLink() {
        Booking booking = remote("PENDING", 5L);
        booking.setRemoteSessionLink("https://meet.google.com/abc-defg-hij");
        assertNull(booking.getRemoteSessionLink());
        booking.setPaymentStatus("PAID");
        assertEquals("https://meet.google.com/abc-defg-hij", booking.getRemoteSessionLink());
    }

    @Test
    void customerCannotProvisionAnotherCustomersBooking() {
        BookingRepository bookings = mock(BookingRepository.class);
        Booking booking = remote("PAID", 5L);
        booking.setCustomerId(7L);
        when(bookings.findById(1L)).thenReturn(Optional.of(booking));
        BookingService service = new BookingService(bookings, mock(TechnicianRepository.class),
                mock(TrustedPricingService.class), mock(NotificationService.class),
                mock(UkEarlyServiceConsentService.class), mock(RemoteSessionProvisioningService.class));
        User otherCustomer = new User();
        otherCustomer.setId(8L);
        otherCustomer.setRole(Role.CUSTOMER);
        assertThrows(RuntimeException.class, () -> service.getBookingForCurrentUser(1L, otherCustomer));
    }

    private Booking remote(String paymentStatus, Long technicianId) {
        Booking booking = new Booking();
        booking.setId(1L);
        booking.setTechnicianId(technicianId);
        booking.setServiceMode(ServiceMode.REMOTE);
        booking.setRemoteSessionRequired(true);
        booking.setPaymentStatus(paymentStatus);
        return booking;
    }
}
