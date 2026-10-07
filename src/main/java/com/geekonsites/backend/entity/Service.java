package com.geekonsites.backend.entity;

import com.geekonsites.backend.enums.ServiceMode;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * PHASE 6 — database-backed service catalog entry.
 *
 * <p>A stable, machine-readable {@code code} is the business identity (unique, non-null);
 * the display {@code name}/prices may be edited by Admin without changing the identity.
 * Pricing lives in {@link ServicePrice} (exact minor units per currency).
 */
@Entity
@Table(name = "services", uniqueConstraints =
        @UniqueConstraint(name = "uq_services_code", columnNames = "code"))
@Data
public class Service {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_mode", nullable = false, length = 20)
    private ServiceMode serviceMode;

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
