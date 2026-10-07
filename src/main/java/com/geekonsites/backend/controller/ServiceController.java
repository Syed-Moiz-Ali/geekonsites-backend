package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.ServiceResponse;
import com.geekonsites.backend.entity.Service;
import com.geekonsites.backend.entity.ServicePrice;
import com.geekonsites.backend.enums.Currency;
import com.geekonsites.backend.service.PaymentMoney;
import com.geekonsites.backend.service.ServiceCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * PHASE 6 — public service-discovery API.
 *
 * <p>Active services with server-authoritative pricing for a US/UK market. Anonymous
 * browsing is allowed (public marketplace). Markets other than US/UK are rejected (400).
 */
@RestController
@RequestMapping("/api/services")
public class ServiceController {

    private final ServiceCatalogService serviceCatalog;

    public ServiceController(ServiceCatalogService serviceCatalog) {
        this.serviceCatalog = serviceCatalog;
    }

    @GetMapping
    public ResponseEntity<List<ServiceResponse>> listServices(
            @RequestParam(name = "market", defaultValue = "US") String market
    ) {
        Currency currency = serviceCatalog.currencyForMarket(market);
        List<Service> active = serviceCatalog.listActiveServices();
        var prices = serviceCatalog.activePriceMinorByServiceId(
                currency, active.stream().map(Service::getId).toList());

        List<ServiceResponse> responses = active.stream()
                .filter(service -> prices.containsKey(service.getId()))
                .map(service -> toResponse(service, currency, prices.get(service.getId())))
                .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{code}")
    public ResponseEntity<ServiceResponse> getService(
            @PathVariable("code") String code,
            @RequestParam(name = "market", defaultValue = "US") String market
    ) {
        Currency currency = serviceCatalog.currencyForMarket(market);
        Service service = serviceCatalog.findActiveByCode(code)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Service not found"));
        ServicePrice price = serviceCatalog.priceFor(service.getId(), currency)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Service is not available for this market."));
        return ResponseEntity.ok(toResponse(service, currency, price.getAmountMinor()));
    }

    private ServiceResponse toResponse(Service service, Currency currency, long amountMinor) {
        return new ServiceResponse(
                service.getId(),
                service.getCode(),
                service.getName(),
                service.getDescription(),
                service.getServiceMode(),
                PaymentMoney.toMajorMoney(amountMinor),
                currency);
    }
}
