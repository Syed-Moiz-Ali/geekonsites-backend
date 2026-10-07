package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Invoice;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.InvoiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InvoiceServiceTest {
    private InvoiceRepository invoices;
    private BookingRepository bookings;
    private InvoiceService service;

    @BeforeEach
    void setUp() {
        invoices = mock(InvoiceRepository.class);
        bookings = mock(BookingRepository.class);
        service = new InvoiceService(invoices, bookings, java.time.Clock.systemUTC());
    }

    @Test
    void balancePendingReturnsPersistedAdvanceInvoiceWithoutRegeneration() {
        Booking booking = booking(8L, "BALANCE_PENDING");
        Invoice invoice = new Invoice();
        invoice.setId(3L);
        invoice.setBookingId(8L);
        when(bookings.findById(8L)).thenReturn(Optional.of(booking));
        when(invoices.findFirstByBookingIdOrderByIdAsc(8L)).thenReturn(Optional.of(invoice));

        assertSame(invoice, service.generateInvoiceFromBooking(8L));
        verify(invoices, never()).save(any());
        verify(bookings, never()).save(any());
    }

    @Test
    void balancePendingWithoutPersistedInvoiceReturnsControlledConflict() {
        when(bookings.findById(8L)).thenReturn(Optional.of(booking(8L, "BALANCE_PENDING")));
        when(invoices.findFirstByBookingIdOrderByIdAsc(8L)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.generateInvoiceFromBooking(8L));
        assertEquals(409, error.getStatusCode().value());
    }

    @Test
    void missingBookingAndInvoiceReturnControlledNotFound() {
        when(bookings.findById(99L)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.generateInvoiceFromBooking(99L)).getStatusCode().value());

        when(invoices.findFirstByBookingIdOrderByIdAsc(99L)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.getInvoiceByBookingId(99L)).getStatusCode().value());
    }

    private Booking booking(Long id, String paymentStatus) {
        Booking booking = new Booking();
        booking.setId(id);
        booking.setPaymentStatus(paymentStatus);
        booking.setTotalAmount(300.0);
        booking.setPaidAmount(90.0);
        return booking;
    }
}
