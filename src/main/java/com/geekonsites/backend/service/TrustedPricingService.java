package com.geekonsites.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekonsites.backend.dto.BookingRequest;
import com.geekonsites.backend.entity.Service;
import com.geekonsites.backend.entity.ServiceAddon;
import com.geekonsites.backend.entity.ServicePrice;
import com.geekonsites.backend.enums.Currency;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.repository.ServiceAddonRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * PHASE 6 — pricing orchestration over the DB-backed catalog.
 *
 * <p>The static service/add-on maps are gone. Base price comes from {@code ServicePrice};
 * add-on price from {@code ServiceAddon}; the platform fee is a separate platform-level
 * policy constant (see Phase 6 report: CLIENT CLARIFICATION REQUIRED). Service mode is
 * authoritative from the catalog and never trusted from the request.
 */
@org.springframework.stereotype.Service
public class TrustedPricingService {

    /** Current flat platform/service fee (major units 12.00 → 1200 minor). Policy unclear → preserved. */
    static final long PLATFORM_FEE_MINOR = 1200L;

    private final ServiceCatalogService serviceCatalog;
    private final ServiceAddonRepository serviceAddonRepository;
    private final ObjectMapper objectMapper;

    public TrustedPricingService(
            ServiceCatalogService serviceCatalog,
            ServiceAddonRepository serviceAddonRepository,
            ObjectMapper objectMapper
    ) {
        this.serviceCatalog = serviceCatalog;
        this.serviceAddonRepository = serviceAddonRepository;
        this.objectMapper = objectMapper;
    }

    public PricingQuote calculatePricing(BookingRequest request) {
        Currency currency = request.getCountry() == null
                ? Currency.USD
                : serviceCatalog.currencyForMarket(request.getCountry());

        String tokens = (request.getServiceCode() != null && !request.getServiceCode().isBlank())
                ? request.getServiceCode()
                : request.getServiceType();
        if (tokens == null || tokens.isBlank()) {
            throw badRequest("A valid service and support method are required.");
        }

        long baseMinor = 0;
        ServiceMode mode = null;
        Service first = null;
        for (String raw : tokens.split(",")) {
            Service service = serviceCatalog.requireActiveByCodeOrName(raw.trim());
            if (mode == null) {
                mode = service.getServiceMode();
            } else if (mode != service.getServiceMode()) {
                throw badRequest("Selected services must share the same support method.");
            }
            ServicePrice price = serviceCatalog.priceFor(service.getId(), currency)
                    .orElseThrow(() -> badRequest("Selected service is not available."));
            baseMinor += price.getAmountMinor();
            if (first == null) {
                first = service;
            }
        }

        // Service mode is authoritative; a client-supplied mode that contradicts it is rejected.
        if (request.getServiceMode() != null && request.getServiceMode() != mode) {
            throw badRequest("Selected service is not available for the chosen support method.");
        }

        long addonMinor = addonTotal(request.getSelectedAddons(), currency);
        long totalMinor = baseMinor + addonMinor + PLATFORM_FEE_MINOR;
        boolean onsite = mode == ServiceMode.ONSITE;
        long advanceMinor = onsite ? Math.round(totalMinor * 0.30) : 0L;
        long remainingMinor = onsite ? totalMinor - advanceMinor : 0L;

        return new PricingQuote(
                first.getId(),
                first.getCode(),
                first.getName(),
                mode,
                currency,
                baseMinor,
                addonMinor,
                0L,
                PLATFORM_FEE_MINOR,
                totalMinor,
                advanceMinor,
                remainingMinor,
                onsite ? "ADVANCE_PAYMENT" : "FULL_PAYMENT",
                request.getSelectedAddons()
        );
    }

    private long addonTotal(String selected, Currency currency) {
        if (selected == null || selected.isBlank()) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(selected);
            if (!root.isArray()) {
                throw badRequest("Invalid add-on selection.");
            }
            long total = 0;
            for (JsonNode item : root) {
                ServiceAddon addon = serviceAddonRepository.findByCode(item.path("id").asText(""))
                        .filter(ServiceAddon::isActive)
                        .orElseThrow(() -> badRequest("Invalid add-on selection."));
                int quantity = item.path("quantity").asInt(0);
                if (quantity < 1 || quantity > 10) {
                    throw badRequest("Invalid add-on selection.");
                }
                total += (currency == Currency.GBP ? addon.getGbpAmountMinor() : addon.getUsdAmountMinor()) * quantity;
            }
            return total;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw badRequest("Invalid add-on selection.");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
