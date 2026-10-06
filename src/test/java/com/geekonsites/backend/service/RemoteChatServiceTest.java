package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.RemoteChatMessage;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.RemoteChatMessageRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.repository.projection.TechnicianAccessView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteChatServiceTest {
    private BookingRepository bookings;
    private RemoteChatMessageRepository messages;
    private TechnicianRepository technicians;
    private RemoteChatService service;

    @BeforeEach
    void setup() {
        bookings = mock(BookingRepository.class);
        messages = mock(RemoteChatMessageRepository.class);
        technicians = mock(TechnicianRepository.class);
        service = new RemoteChatService(bookings, messages, technicians);
        when(bookings.findById(1L)).thenReturn(Optional.of(booking()));
        when(messages.save(any())).thenAnswer(call -> { RemoteChatMessage value = call.getArgument(0); value.setId(10L); return value; });
    }

    @Test
    void customerCanSendAndBackendDeterminesIdentity() {
        RemoteChatMessage saved = service.send(1L, "  Hello, I am ready.  ", user(7L, Role.CUSTOMER, "customer@example.com"));
        assertEquals(7L, saved.getSenderUserId());
        assertEquals("CUSTOMER", saved.getSenderRole());
        assertEquals("Hello, I am ready.", saved.getMessage());
        assertEquals(1L, saved.getBookingId());
    }

    @Test
    void assignedTechnicianCanReply() {
        TechnicianAccessView technician = technicianAccess(5L);
        when(technicians.findAccessByEmail("tech@example.com")).thenReturn(Optional.of(technician));
        RemoteChatMessage saved = service.send(1L, "Okay, joining now.", user(22L, Role.TECHNICIAN, "tech@example.com"));
        assertEquals(22L, saved.getSenderUserId());
        assertEquals("TECHNICIAN", saved.getSenderRole());
    }

    @Test
    void anotherCustomerAndUnrelatedTechnicianReceiveForbidden() {
        assertThrows(ResponseStatusException.class, () -> service.history(1L, user(8L, Role.CUSTOMER, "other@example.com")));
        TechnicianAccessView other = technicianAccess(9L);
        when(technicians.findAccessByEmail("othertech@example.com")).thenReturn(Optional.of(other));
        assertThrows(ResponseStatusException.class, () -> service.history(1L, user(30L, Role.TECHNICIAN, "othertech@example.com")));
    }

    @Test
    void unauthenticatedAndUnpaidAccessAreRejected() {
        assertThrows(ResponseStatusException.class, () -> service.history(1L, null));
        Booking booking = booking(); booking.setPaymentStatus("FAILED");
        when(bookings.findById(1L)).thenReturn(Optional.of(booking));
        assertThrows(ResponseStatusException.class, () -> service.history(1L, user(7L, Role.CUSTOMER, "customer@example.com")));
    }

    @Test
    void emptyMessageIsRejectedAndNotPersisted() {
        assertThrows(ResponseStatusException.class, () -> service.send(1L, "   ", user(7L, Role.CUSTOMER, "customer@example.com")));
        verify(messages, never()).save(any());
    }

    @Test
    void persistedHistoryIsReturnedChronologically() {
        RemoteChatMessage first = message(1L, LocalDateTime.of(2026, 8, 21, 10, 0));
        RemoteChatMessage second = message(2L, LocalDateTime.of(2026, 8, 21, 10, 1));
        when(messages.findByBookingIdOrderByCreatedAtAscIdAsc(1L)).thenReturn(List.of(first, second));
        List<RemoteChatMessage> history = service.history(1L, user(7L, Role.CUSTOMER, "customer@example.com"));
        assertEquals(List.of(1L, 2L), history.stream().map(RemoteChatMessage::getId).toList());
    }

    private Booking booking() { Booking value = new Booking(); value.setId(1L); value.setCustomerId(7L); value.setTechnicianId(5L); value.setServiceMode(ServiceMode.REMOTE); value.setPaymentStatus("PAID"); return value; }
    private User user(Long id, Role role, String email) { User value = new User(); value.setId(id); value.setRole(role); value.setEmail(email); return value; }
    private TechnicianAccessView technicianAccess(Long id) { TechnicianAccessView value = mock(TechnicianAccessView.class); when(value.getId()).thenReturn(id); return value; }
    private RemoteChatMessage message(Long id, LocalDateTime time) { RemoteChatMessage value = new RemoteChatMessage(); value.setId(id); value.setCreatedAt(time); return value; }
}
