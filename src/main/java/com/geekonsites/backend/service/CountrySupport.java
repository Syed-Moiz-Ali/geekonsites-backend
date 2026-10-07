package com.geekonsites.backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

/**
 * PHASE 5 — US/UK country normalization for customer registration.
 *
 * <p>The platform supports the United States and United Kingdom only. Unsupported,
 * blank or null values are rejected (never silently defaulted to US). Country drives
 * server-authoritative currency (US → USD, UK → GBP).
 */
public final class CountrySupport {

    private CountrySupport() {
    }

    /**
     * @return canonical {@code "US"} or {@code "UK"}
     * @throws ResponseStatusException 400 for null/blank/unsupported values
     */
    public static String normalize(String country) {
        String value = country == null ? "" : country.trim().toUpperCase(Locale.ROOT).replace('_', ' ');
        switch (value) {
            case "US":
            case "USA":
            case "UNITED STATES":
            case "UNITED STATES OF AMERICA":
                return "US";
            case "UK":
            case "GB":
            case "GBR":
            case "UNITED KINGDOM":
            case "GREAT BRITAIN":
                return "UK";
            default:
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "GeekOnSites currently supports United States and United Kingdom registrations only.");
        }
    }
}
