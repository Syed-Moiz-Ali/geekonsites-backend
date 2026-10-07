package com.geekonsites.backend.entity;

import com.geekonsites.backend.enums.Currency;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * PHASE 6 — exact current price for one service in one currency.
 *
 * <p>Money is stored as integer minor units. One current price per (service, currency);
 * enabled by the unique constraint. Historical booking prices are preserved separately via
 * the booking snapshot, so no temporal price table is required here.
 */
@Entity
@Table(name = "service_prices",
        uniqueConstraints = @UniqueConstraint(name = "uq_service_price_currency", columnNames = {"service_id", "currency"}),
        indexes = @Index(name = "idx_service_price_service", columnList = "service_id"))
@Data
public class ServicePrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(name = "amount_minor", nullable = false)
    private Long amountMinor;

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
