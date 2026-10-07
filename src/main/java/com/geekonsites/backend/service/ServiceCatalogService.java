package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Service;
import com.geekonsites.backend.entity.ServicePrice;
import com.geekonsites.backend.enums.Currency;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.repository.ServicePriceRepository;
import com.geekonsites.backend.repository.ServiceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PHASE 6 — database-backed service catalog.
 *
 * <p>Single source of truth for services and their exact per-currency prices. Provides
 * public discovery (active services) and Admin management (all services). Uses
 * {@link CountrySupport} for US/UK market validation.
 */
@org.springframework.stereotype.Service
public class ServiceCatalogService {

    private final ServiceRepository serviceRepository;
    private final ServicePriceRepository servicePriceRepository;

    public ServiceCatalogService(ServiceRepository serviceRepository, ServicePriceRepository servicePriceRepository) {
        this.serviceRepository = serviceRepository;
        this.servicePriceRepository = servicePriceRepository;
    }

    // ============================================================== markets

    public Currency currencyForMarket(String market) {
        String country = CountrySupport.normalize(market);
        return "UK".equals(country) ? Currency.GBP : Currency.USD;
    }

    // ============================================================== public reads

    public List<Service> listActiveServices() {
        return serviceRepository.findByActiveTrueOrderBySortOrderAscNameAsc();
    }

    public List<Service> listAllServices() {
        return serviceRepository.findAllByOrderBySortOrderAscNameAsc();
    }

    public Service getById(Long id) {
        return serviceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found"));
    }

    /** Public detail lookup: active service by code only. */
    public Optional<Service> findActiveByCode(String code) {
        return serviceRepository.findByCodeIgnoreCase(code).filter(Service::isActive);
    }

    public Optional<ServicePrice> priceFor(Long serviceId, Currency currency) {
        return servicePriceRepository.findByServiceIdAndCurrency(serviceId, currency);
    }

    public Map<Long, Long> activePriceMinorByServiceId(Currency currency, Collection<Long> serviceIds) {
        if (serviceIds.isEmpty()) {
            return Map.of();
        }
        return servicePriceRepository.findByServiceIdIn(serviceIds).stream()
                .filter(price -> price.getCurrency() == currency)
                .collect(Collectors.toMap(ServicePrice::getServiceId, ServicePrice::getAmountMinor, (a, b) -> a));
    }

    /** Resolves an active service by stable code (preferred) or legacy display name. */
    public Service requireActiveByCodeOrName(String token) {
        if (token == null || token.isBlank()) {
            throw badRequest("Selected service is not available.");
        }
        String value = token.trim();
        Service service = serviceRepository.findByCodeIgnoreCase(value)
                .or(() -> serviceRepository.findByNameIgnoreCase(value))
                .orElseThrow(() -> badRequest("Selected service is not available."));
        if (!service.isActive()) {
            throw badRequest("Selected service is not available.");
        }
        return service;
    }

    // ============================================================== admin writes

    @Transactional
    public Service createService(String code, String name, String description, ServiceMode mode,
                                 Integer sortOrder, Boolean active, Long usdMinor, Long gbpMinor) {
        String normalizedCode = normalizeCode(code);
        if (normalizedCode.isBlank()) {
            throw badRequest("Service code is required");
        }
        if (name == null || name.isBlank()) {
            throw badRequest("Service name is required");
        }
        if (mode == null) {
            throw badRequest("Service mode is required");
        }
        if (usdMinor == null || usdMinor <= 0 || gbpMinor == null || gbpMinor <= 0) {
            throw badRequest("A positive USD and GBP price are required");
        }
        if (serviceRepository.existsByCodeIgnoreCase(normalizedCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A service with this code already exists");
        }

        Service service = new Service();
        service.setCode(normalizedCode);
        service.setName(name.trim());
        service.setDescription(description);
        service.setServiceMode(mode);
        service.setSortOrder(sortOrder);
        service.setActive(active == null || active);
        try {
            service = serviceRepository.saveAndFlush(service);
        } catch (DataIntegrityViolationException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A service with this code already exists");
        }

        upsertPrice(service.getId(), Currency.USD, usdMinor);
        upsertPrice(service.getId(), Currency.GBP, gbpMinor);
        return service;
    }

    @Transactional
    public Service updateService(Long id, String name, String description, ServiceMode mode,
                                 Integer sortOrder, Boolean active, Long usdMinor, Long gbpMinor) {
        Service service = getById(id);
        if (name != null && !name.isBlank()) service.setName(name.trim());
        if (description != null) service.setDescription(description);
        if (mode != null) service.setServiceMode(mode);
        if (sortOrder != null) service.setSortOrder(sortOrder);
        if (active != null) service.setActive(active);
        service = serviceRepository.save(service);

        if (usdMinor != null) upsertPrice(id, Currency.USD, requirePositive(usdMinor, "USD"));
        if (gbpMinor != null) upsertPrice(id, Currency.GBP, requirePositive(gbpMinor, "GBP"));
        return service;
    }

    @Transactional
    public Service setActive(Long id, boolean active) {
        Service service = getById(id);
        service.setActive(active);
        return serviceRepository.save(service);
    }

    private void upsertPrice(Long serviceId, Currency currency, long amountMinor) {
        ServicePrice price = servicePriceRepository.findByServiceIdAndCurrency(serviceId, currency)
                .orElseGet(ServicePrice::new);
        price.setServiceId(serviceId);
        price.setCurrency(currency);
        price.setAmountMinor(amountMinor);
        servicePriceRepository.save(price);
    }

    private long requirePositive(long amountMinor, String label) {
        if (amountMinor <= 0) {
            throw badRequest("A positive " + label + " price is required");
        }
        return amountMinor;
    }

    private String normalizeCode(String code) {
        if (code == null) return "";
        return code.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
