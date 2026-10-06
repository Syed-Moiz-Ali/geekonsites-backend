package com.geekonsites.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "invoices")
@Data
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String invoiceNumber;

    @Column(unique = true, nullable = false)
    private Long bookingId;

    private Long customerId;

    private Long technicianId;

    private String customerName;

    private String technicianName;

    private String serviceType;

    private Double amount;

    private String currency;

    private String paymentStatus;

    private String paymentMethod;

    private String paymentTransactionId;

    private Double paidAmount;

    private LocalDateTime issuedAt;

    @PrePersist
    void onCreate() {
        if (issuedAt == null) issuedAt = LocalDateTime.now();
    }
}
