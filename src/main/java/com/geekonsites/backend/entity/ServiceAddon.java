package com.geekonsites.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * PHASE 6 — globally-applicable billable add-on with exact USD/GBP minor-unit prices.
 *
 * <p>Add-ons were already used by booking pricing (via a hardcoded map); they are migrated
 * to a minimal persistent model. US/UK-only scope means two exact minor-unit columns are
 * sufficient (no arbitrary-currency price table).
 */
@Entity
@Table(name = "service_addons", uniqueConstraints =
        @UniqueConstraint(name = "uq_service_addons_code", columnNames = "code"))
@Data
public class ServiceAddon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "usd_amount_minor", nullable = false)
    private Long usdAmountMinor;

    @Column(name = "gbp_amount_minor", nullable = false)
    private Long gbpAmountMinor;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean active = true;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
