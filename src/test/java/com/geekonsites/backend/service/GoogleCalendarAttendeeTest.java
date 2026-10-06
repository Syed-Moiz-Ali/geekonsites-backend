package com.geekonsites.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.repository.TechnicianRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleCalendarAttendeeTest {

    @Test
    void includesCustomerAndOnlyAssignedTechnicianPersonalEmail() {
        TechnicianRepository technicians = mock(TechnicianRepository.class);
        Technician assigned = technician(10L, "assigned.personal@example.com", "generated@gos.com");
        Technician wrong = technician(11L, "wrong.personal@example.com", "wrong@gos.com");
        when(technicians.findById(10L)).thenReturn(Optional.of(assigned));
        when(technicians.findById(11L)).thenReturn(Optional.of(wrong));
        GoogleCalendarService calendar = new GoogleCalendarService(technicians);
        Booking booking = booking(10L, "customer@example.com");

        List<String> attendees = calendar.attendeeEmails(booking);

        assertEquals(List.of("customer@example.com", "assigned.personal@example.com"), attendees);
        assertFalse(attendees.contains("wrong.personal@example.com"));
        assertFalse(attendees.contains("generated@gos.com"));
        verify(technicians).findById(10L);
        verify(technicians, never()).findById(11L);
    }

    @Test
    void duplicateCustomerAndTechnicianIdentityAppearsOnlyOnce() {
        TechnicianRepository technicians = mock(TechnicianRepository.class);
        when(technicians.findById(10L)).thenReturn(Optional.of(
                technician(10L, " Customer@Example.com ", "generated@gos.com")));
        GoogleCalendarService calendar = new GoogleCalendarService(technicians);

        assertEquals(List.of("customer@example.com"),
                calendar.attendeeEmails(booking(10L, "customer@example.com")));
    }

    @Test
    void reassignmentResolvesNewTechnicianAndDropsOldTechnician() {
        TechnicianRepository technicians = mock(TechnicianRepository.class);
        when(technicians.findById(10L)).thenReturn(Optional.of(technician(10L, "old@example.com", null)));
        when(technicians.findById(22L)).thenReturn(Optional.of(technician(22L, "new@example.com", null)));
        GoogleCalendarService calendar = new GoogleCalendarService(technicians);
        Booking booking = booking(10L, "customer@example.com");
        assertTrue(calendar.attendeeEmails(booking).contains("old@example.com"));

        booking.setTechnicianId(22L);
        List<String> reassigned = calendar.attendeeEmails(booking);

        assertEquals(List.of("customer@example.com", "new@example.com"), reassigned);
        assertFalse(reassigned.contains("old@example.com"));
    }

    @Test
    void technicianPersonalEmailIsNotSerializedInCustomerBookingPayload() throws Exception {
        TechnicianRepository technicians = mock(TechnicianRepository.class);
        when(technicians.findById(10L)).thenReturn(Optional.of(technician(10L, "private.tech@example.com", null)));
        GoogleCalendarService calendar = new GoogleCalendarService(technicians);
        Booking booking = booking(10L, "customer@example.com");
        assertTrue(calendar.attendeeEmails(booking).contains("private.tech@example.com"));

        String customerFacingBookingJson = new ObjectMapper().writeValueAsString(booking);

        assertFalse(customerFacingBookingJson.contains("private.tech@example.com"));
        assertFalse(customerFacingBookingJson.contains("technicianEmail"));
        assertFalse(customerFacingBookingJson.contains("personalEmail"));
    }

    private Booking booking(Long technicianId, String customerEmail) {
        Booking booking = new Booking();
        booking.setId(1L);
        booking.setTechnicianId(technicianId);
        booking.setCustomerEmail(customerEmail);
        return booking;
    }

    private Technician technician(Long id, String personalEmail, String companyEmail) {
        Technician technician = new Technician();
        technician.setId(id);
        technician.setPersonalEmail(personalEmail);
        technician.setCompanyEmail(companyEmail);
        return technician;
    }
}
