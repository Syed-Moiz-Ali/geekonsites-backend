package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Invoice;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.InvoiceRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** PHASE 9 — one invoice authority, dynamic year, idempotent regeneration. */
class InvoiceNumberingTest {

    @Test
    void invoiceNumberYearIsDerivedFromTheClockNotHardcoded() {
        assertEquals("GOS-US-2026-8", numberFor(clockAt("2026-05-01T00:00:00Z")));
        assertEquals("GOS-US-2027-8", numberFor(clockAt("2027-01-02T00:00:00Z")));
        assertEquals("GOS-UK-2030-8", numberForGbp(clockAt("2030-06-01T00:00:00Z")));
    }

    @Test
    void repeatedGenerationReturnsTheSamePersistedInvoice() {
        InvoiceRepository invoices = mock(InvoiceRepository.class);
        BookingRepository bookings = mock(BookingRepository.class);
        InvoiceService service = new InvoiceService(invoices, bookings, clockAt("2026-05-01T00:00:00Z"));
        Booking booking = booking("US");
        Invoice existing = new Invoice();
        existing.setId(3L);
        existing.setBookingId(8L);
        existing.setInvoiceNumber("GOS-US-2026-8");
        when(bookings.findById(8L)).thenReturn(Optional.of(booking));
        when(invoices.findFirstByBookingIdOrderByIdAsc(8L)).thenReturn(Optional.of(existing));
        when(invoices.save(any(Invoice.class))).thenAnswer(call -> call.getArgument(0));

        Invoice first = service.generateInvoiceFromBooking(8L);
        Invoice second = service.generateInvoiceFromBooking(8L);

        assertSame(first, second);
        assertEquals("GOS-US-2026-8", second.getInvoiceNumber());
    }

    @Test
    void invoiceUsesBookingExactSnapshotNotLivePricing() {
        InvoiceRepository invoices = mock(InvoiceRepository.class);
        BookingRepository bookings = mock(BookingRepository.class);
        InvoiceService service = new InvoiceService(invoices, bookings, clockAt("2026-05-01T00:00:00Z"));
        Booking booking = booking("US");
        booking.setTotalAmountMinor(12345L);
        booking.setPaidAmountMinor(2345L);
        when(bookings.findById(8L)).thenReturn(Optional.of(booking));
        when(invoices.findFirstByBookingIdOrderByIdAsc(8L)).thenReturn(Optional.empty());
        when(invoices.saveAndFlush(any(Invoice.class))).thenAnswer(call -> call.getArgument(0));

        Invoice invoice = service.generateInvoiceFromBooking(8L);

        assertEquals(12345L, invoice.getAmountMinor());
        assertEquals(2345L, invoice.getPaidAmountMinor());
    }

    private String numberFor(Clock clock) {
        InvoiceRepository invoices = mock(InvoiceRepository.class);
        BookingRepository bookings = mock(BookingRepository.class);
        InvoiceService service = new InvoiceService(invoices, bookings, clock);
        when(bookings.findById(8L)).thenReturn(Optional.of(booking("US")));
        when(invoices.findFirstByBookingIdOrderByIdAsc(8L)).thenReturn(Optional.empty());
        when(invoices.saveAndFlush(any(Invoice.class))).thenAnswer(call -> call.getArgument(0));
        return service.generateInvoiceFromBooking(8L).getInvoiceNumber();
    }

    private String numberForGbp(Clock clock) {
        InvoiceRepository invoices = mock(InvoiceRepository.class);
        BookingRepository bookings = mock(BookingRepository.class);
        InvoiceService service = new InvoiceService(invoices, bookings, clock);
        when(bookings.findById(8L)).thenReturn(Optional.of(booking("UK")));
        when(invoices.findFirstByBookingIdOrderByIdAsc(8L)).thenReturn(Optional.empty());
        when(invoices.saveAndFlush(any(Invoice.class))).thenAnswer(call -> call.getArgument(0));
        return service.generateInvoiceFromBooking(8L).getInvoiceNumber();
    }

    private Booking booking(String country) {
        Booking booking = new Booking();
        booking.setId(8L);
        booking.setCustomerId(7L);
        booking.setCountry(country);
        booking.setCurrency("UK".equals(country) ? "GBP" : "USD");
        booking.setPaymentStatus("PAID");
        booking.setTotalAmountMinor(30000L);
        booking.setPaidAmountMinor(9000L);
        return booking;
    }

    private Clock clockAt(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }
}
