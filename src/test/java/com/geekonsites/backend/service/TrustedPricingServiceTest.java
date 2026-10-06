package com.geekonsites.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekonsites.backend.dto.BookingRequest;
import com.geekonsites.backend.enums.ServiceMode;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

class TrustedPricingServiceTest {
    private final TrustedPricingService pricing = new TrustedPricingService(new ObjectMapper());

    @Test
    void calculatesUsRemotePriceFromServerCatalog() {
        BookingRequest request = request("US", ServiceMode.REMOTE, "Virus & Malware Removal");
        request.setBaseAmount(0.01);
        request.setAddonsAmount(0.01);
        request.setSelectedAddons("[{\"id\":\"priority-support\",\"quantity\":2}]");

        BookingRequest result = pricing.calculatePricing(request);

        assertEquals("USD", result.getCurrency());
        assertEquals(79.0, result.getBaseAmount());
        assertEquals(38.0, result.getAddonsAmount());
        assertEquals(12.0, result.getPlatformFee());
        assertEquals(129.0, result.getTotalAmount());
        assertEquals(0.0, result.getAdvanceAmount());
    }

    @Test
    void calculatesUkOnsiteAdvanceFromServerCatalog() {
        BookingRequest result = pricing.calculatePricing(
                request("United Kingdom", ServiceMode.ONSITE, "Laptop Repair")
        );

        assertEquals("GBP", result.getCurrency());
        assertEquals(109.0, result.getBaseAmount());
        assertEquals(121.0, result.getTotalAmount());
        assertEquals(36.3, result.getAdvanceAmount());
        assertEquals(84.7, result.getRemainingAmount());
    }

    @Test
    void rejectsServiceModeTampering() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> pricing.calculatePricing(
                request("US", ServiceMode.REMOTE, "Laptop Repair")
        ));
        assertEquals(BAD_REQUEST, error.getStatusCode());
        assertEquals("Selected service is not available for the chosen support method.", error.getReason());
        assertFalse(error.getReason().contains("RuntimeException"));
    }

    @Test
    void rejectsUnknownAddon() {
        BookingRequest request = request("US", ServiceMode.REMOTE, "PC Health Check & Diagnosis");
        request.setSelectedAddons("[{\"id\":\"free-everything\",\"quantity\":1}]");
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> pricing.calculatePricing(request));
        assertEquals(BAD_REQUEST, error.getStatusCode());
        assertEquals("Invalid add-on selection.", error.getReason());
    }

    @Test
    void rejectsRemoteOnlyServiceForOnsite() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> pricing.calculatePricing(
                request("US", ServiceMode.ONSITE, "Virus & Malware Removal")
        ));
        assertEquals(BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void rejectsUnsupportedCountryAndDoesNotTrustCurrency() {
        BookingRequest unsupported = request("India", ServiceMode.REMOTE, "Virus & Malware Removal");
        assertEquals(BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> pricing.calculatePricing(unsupported)).getStatusCode());

        BookingRequest us = request("US", ServiceMode.REMOTE, "Virus & Malware Removal");
        us.setCurrency("GBP");
        us.setTotalAmount(0.01);
        BookingRequest result = pricing.calculatePricing(us);
        assertEquals("USD", result.getCurrency());
        assertEquals(91.0, result.getTotalAmount());
    }

    @Test
    void validOnsiteAndRemoteServicesWorkInUsdAndGbp() {
        assertEquals(141.0, pricing.calculatePricing(request("US", ServiceMode.ONSITE, "Laptop Repair")).getTotalAmount());
        assertEquals(121.0, pricing.calculatePricing(request("UK", ServiceMode.ONSITE, "Laptop Repair")).getTotalAmount());
        assertEquals(91.0, pricing.calculatePricing(request("US", ServiceMode.REMOTE, "Virus & Malware Removal")).getTotalAmount());
        assertEquals(81.0, pricing.calculatePricing(request("UK", ServiceMode.REMOTE, "Virus & Malware Removal")).getTotalAmount());
    }

    private BookingRequest request(String country, ServiceMode mode, String service) {
        BookingRequest request = new BookingRequest();
        request.setCountry(country);
        request.setServiceMode(mode);
        request.setServiceType(service);
        return request;
    }
}
