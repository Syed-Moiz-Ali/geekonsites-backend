package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.BookingRequest;
import com.geekonsites.backend.enums.Currency;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.support.Phase0IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * PHASE 6 — pricing now reads the DB-backed service catalog (seeded from the former
 * hardcoded map). Values are exact minor units and match the historical catalog.
 */
class TrustedPricingServiceTest extends Phase0IntegrationTestSupport {

    @Autowired TrustedPricingService pricing;

    @Test
    void calculatesUsRemotePriceFromServerCatalog() {
        BookingRequest request = request("US", ServiceMode.REMOTE, "Virus & Malware Removal");
        request.setSelectedAddons("[{\"id\":\"priority-support\",\"quantity\":2}]");

        PricingQuote quote = pricing.calculatePricing(request);

        assertEquals(Currency.USD, quote.currency());
        assertEquals(7900L, quote.baseAmountMinor());
        assertEquals(3800L, quote.addonAmountMinor());
        assertEquals(1200L, quote.platformFeeMinor());
        assertEquals(12900L, quote.totalAmountMinor());
        assertEquals(0L, quote.advanceAmountMinor());
    }

    @Test
    void calculatesUkOnsiteAdvanceFromServerCatalog() {
        PricingQuote quote = pricing.calculatePricing(
                request("United Kingdom", ServiceMode.ONSITE, "Laptop Repair"));

        assertEquals(Currency.GBP, quote.currency());
        assertEquals(10900L, quote.baseAmountMinor());
        assertEquals(12100L, quote.totalAmountMinor());
        assertEquals(3630L, quote.advanceAmountMinor());
        assertEquals(8470L, quote.remainingAmountMinor());
    }

    @Test
    void rejectsServiceModeTampering() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> pricing.calculatePricing(
                request("US", ServiceMode.REMOTE, "Laptop Repair")));
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
                request("US", ServiceMode.ONSITE, "Virus & Malware Removal")));
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
        PricingQuote quote = pricing.calculatePricing(us);
        assertEquals(Currency.USD, quote.currency());
        assertEquals(9100L, quote.totalAmountMinor());
    }

    @Test
    void validOnsiteAndRemoteServicesWorkInUsdAndGbp() {
        assertEquals(14100L, pricing.calculatePricing(request("US", ServiceMode.ONSITE, "Laptop Repair")).totalAmountMinor());
        assertEquals(12100L, pricing.calculatePricing(request("UK", ServiceMode.ONSITE, "Laptop Repair")).totalAmountMinor());
        assertEquals(9100L, pricing.calculatePricing(request("US", ServiceMode.REMOTE, "Virus & Malware Removal")).totalAmountMinor());
        assertEquals(8100L, pricing.calculatePricing(request("UK", ServiceMode.REMOTE, "Virus & Malware Removal")).totalAmountMinor());
    }

    private BookingRequest request(String country, ServiceMode mode, String service) {
        BookingRequest request = new BookingRequest();
        request.setCountry(country);
        request.setServiceMode(mode);
        request.setServiceType(service);
        return request;
    }
}
