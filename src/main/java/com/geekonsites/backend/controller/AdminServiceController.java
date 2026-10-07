package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.AdminServiceCreateRequest;
import com.geekonsites.backend.dto.AdminServiceResponse;
import com.geekonsites.backend.dto.AdminServiceUpdateRequest;
import com.geekonsites.backend.dto.ServiceStatusRequest;
import com.geekonsites.backend.entity.Service;
import com.geekonsites.backend.entity.ServicePrice;
import com.geekonsites.backend.enums.Currency;
import com.geekonsites.backend.service.PaymentMoney;
import com.geekonsites.backend.service.ServiceCatalogService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * PHASE 6 — Admin-only service catalog management.
 *
 * <p>SecurityConfig requires ROLE_ADMIN for {@code /api/admin/**}. No hard delete: services
 * referenced by historical bookings are deactivated instead.
 */
@RestController
@RequestMapping("/api/admin/services")
public class AdminServiceController {

    private final ServiceCatalogService serviceCatalog;

    public AdminServiceController(ServiceCatalogService serviceCatalog) {
        this.serviceCatalog = serviceCatalog;
    }

    @GetMapping
    public ResponseEntity<List<AdminServiceResponse>> listAll() {
        return ResponseEntity.ok(serviceCatalog.listAllServices().stream().map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminServiceResponse> getOne(@PathVariable Long id) {
        return ResponseEntity.ok(toResponse(serviceCatalog.getById(id)));
    }

    @PostMapping
    public ResponseEntity<AdminServiceResponse> create(@Valid @RequestBody AdminServiceCreateRequest request) {
        Service service = serviceCatalog.createService(
                request.code(),
                request.name(),
                request.description(),
                request.serviceMode(),
                request.sortOrder(),
                request.active(),
                PaymentMoney.toMinor(request.usdPrice()),
                PaymentMoney.toMinor(request.gbpPrice()));
        return ResponseEntity.ok(toResponse(service));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AdminServiceResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody AdminServiceUpdateRequest request
    ) {
        Service service = serviceCatalog.updateService(
                id,
                request.name(),
                request.description(),
                request.serviceMode(),
                request.sortOrder(),
                request.active(),
                request.usdPrice() == null ? null : PaymentMoney.toMinor(request.usdPrice()),
                request.gbpPrice() == null ? null : PaymentMoney.toMinor(request.gbpPrice()));
        return ResponseEntity.ok(toResponse(service));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<AdminServiceResponse> setStatus(
            @PathVariable Long id,
            @Valid @RequestBody ServiceStatusRequest request
    ) {
        return ResponseEntity.ok(toResponse(serviceCatalog.setActive(id, request.active())));
    }

    private AdminServiceResponse toResponse(Service service) {
        BigDecimal usd = serviceCatalog.priceFor(service.getId(), Currency.USD)
                .map(ServicePrice::getAmountMinor).map(PaymentMoney::toMajorMoney).orElse(null);
        BigDecimal gbp = serviceCatalog.priceFor(service.getId(), Currency.GBP)
                .map(ServicePrice::getAmountMinor).map(PaymentMoney::toMajorMoney).orElse(null);
        return new AdminServiceResponse(
                service.getId(),
                service.getCode(),
                service.getName(),
                service.getDescription(),
                service.getServiceMode(),
                service.isActive(),
                service.getSortOrder(),
                usd,
                gbp);
    }

    // PHASE 7: validation/duplicate/domain failures are rendered by the central
    // GlobalExceptionHandler using the standard ApiErrorResponse contract.
}
