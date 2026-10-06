package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UkEarlyServiceConsentServiceTest {

    private BookingRepository bookingRepository;
    private UkEarlyServiceConsentService service;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        service = new UkEarlyServiceConsentService(
                bookingRepository,
                Clock.fixed(Instant.parse("2026-08-21T10:15:30Z"), ZoneOffset.UTC)
        );
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void ukBookingRequiresExplicitConsentForInitialPayment() {
        Booking booking = booking("UK", 7L);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.recordForInitialPayment(booking, 7L, "FULL", false));

        assertTrue(error.getMessage().contains("required"));
        verifyNoInteractions(bookingRepository);
    }

    @Test
    void ukConsentUsesBackendClockAndStoresTextVersion() {
        Booking booking = booking("United Kingdom", 7L);

        service.recordForInitialPayment(booking, 7L, "ADVANCE", true);

        assertTrue(booking.getUkEarlyServiceConsent());
        assertEquals(LocalDateTime.of(2026, 8, 21, 10, 15, 30), booking.getUkEarlyServiceConsentAt());
        assertEquals(UkEarlyServiceConsentService.TEXT_VERSION, booking.getUkEarlyServiceConsentTextVersion());
        verify(bookingRepository).save(booking);
    }

    @Test
    void usaBookingDoesNotRequireOrStoreUkConsent() {
        Booking booking = booking("US", 7L);

        service.recordForInitialPayment(booking, 7L, "FULL", false);

        assertFalse(booking.getUkEarlyServiceConsent());
        assertNull(booking.getUkEarlyServiceConsentAt());
        verifyNoInteractions(bookingRepository);
    }

    @Test
    void customerCannotConsentToAnotherCustomersBooking() {
        Booking booking = booking("UK", 7L);

        assertThrows(RuntimeException.class,
                () -> service.recordForInitialPayment(booking, 99L, "FULL", true));
        verifyNoInteractions(bookingRepository);
    }

    @Test
    void consentCannotBeCreatedOrOverwrittenAfterServiceStarts() {
        Booking startedWithoutConsent = booking("UK", 7L);
        startedWithoutConsent.setServiceStartedAt(LocalDateTime.of(2026, 8, 21, 9, 0));
        assertThrows(RuntimeException.class,
                () -> service.recordForInitialPayment(startedWithoutConsent, 7L, "FULL", true));

        Booking alreadyConsented = booking("UK", 7L);
        LocalDateTime originalTimestamp = LocalDateTime.of(2026, 8, 20, 9, 0);
        alreadyConsented.setUkEarlyServiceConsent(true);
        alreadyConsented.setUkEarlyServiceConsentAt(originalTimestamp);
        alreadyConsented.setUkEarlyServiceConsentTextVersion("original-version");
        alreadyConsented.setRemoteSessionStartedAt(LocalDateTime.of(2026, 8, 21, 9, 0));

        service.recordForInitialPayment(alreadyConsented, 7L, "FULL", true);

        assertEquals(originalTimestamp, alreadyConsented.getUkEarlyServiceConsentAt());
        assertEquals("original-version", alreadyConsented.getUkEarlyServiceConsentTextVersion());
        verifyNoInteractions(bookingRepository);
    }

    @Test
    void ukServiceCannotStartWithoutStoredConsent() {
        Booking booking = booking("GB", 7L);
        assertThrows(RuntimeException.class, () -> service.validateBeforeServiceStart(booking));

        booking.setUkEarlyServiceConsent(true);
        booking.setUkEarlyServiceConsentAt(LocalDateTime.of(2026, 8, 21, 10, 15));
        assertDoesNotThrow(() -> service.validateBeforeServiceStart(booking));
    }

    private Booking booking(String country, Long customerId) {
        Booking booking = new Booking();
        booking.setCountry(country);
        booking.setCustomerId(customerId);
        booking.setUkEarlyServiceConsent(false);
        return booking;
    }
}
