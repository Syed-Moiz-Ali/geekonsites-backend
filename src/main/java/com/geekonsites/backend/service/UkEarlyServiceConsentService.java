package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.repository.BookingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class UkEarlyServiceConsentService {

    public static final String TEXT_VERSION = "uk-early-service-consent-v1-2026-08-21";

    private final BookingRepository bookingRepository;
    private final Clock clock;

    public UkEarlyServiceConsentService(BookingRepository bookingRepository, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.clock = clock;
    }

    @Transactional
    public Booking recordForInitialPayment(
            Booking booking,
            Long authenticatedCustomerId,
            String paymentType,
            Boolean consentRequested
    ) {
        if (!authenticatedCustomerId.equals(booking.getCustomerId())) {
            throw new RuntimeException("You cannot provide consent for another customer's booking");
        }

        if (!requiresEarlyServiceConsent(booking) || "REMAINING".equals(paymentType)) {
            return booking;
        }

        if (Boolean.TRUE.equals(booking.getUkEarlyServiceConsent())) {
            return booking;
        }

        if (!Boolean.TRUE.equals(consentRequested)) {
            throw new RuntimeException("UK early-service consent is required before payment");
        }

        if (hasServiceStarted(booking)) {
            throw new RuntimeException("UK early-service consent cannot be recorded after service has started");
        }

        booking.setUkEarlyServiceConsent(true);
        booking.setUkEarlyServiceConsentAt(LocalDateTime.now(clock));
        booking.setUkEarlyServiceConsentTextVersion(TEXT_VERSION);
        return bookingRepository.save(booking);
    }

    public void validateBeforeServiceStart(Booking booking) {
        if (requiresEarlyServiceConsent(booking)
                && (!Boolean.TRUE.equals(booking.getUkEarlyServiceConsent())
                || booking.getUkEarlyServiceConsentAt() == null)) {
            throw new RuntimeException("UK early-service consent is required before service can start");
        }
    }

    boolean isUkBooking(Booking booking) {
        String country = booking.getCountry();
        if (country == null) return false;
        String normalized = country.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("UK")
                || normalized.equals("GB")
                || normalized.equals("UNITED KINGDOM")
                || normalized.equals("GREAT BRITAIN");
    }

    boolean requiresEarlyServiceConsent(Booking booking) {
        if (!isUkBooking(booking)) return false;
        if (booking.getCreatedAt() == null || booking.getBookingDate() == null) return true;
        return !booking.getBookingDate().isAfter(booking.getCreatedAt().toLocalDate().plusDays(14));
    }

    private boolean hasServiceStarted(Booking booking) {
        return booking.getServiceStartedAt() != null
                || booking.getRemoteSessionStartedAt() != null;
    }
}
