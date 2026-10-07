package com.geekonsites.backend.entity;

import com.geekonsites.backend.service.PaymentMoney;
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

    // PHASE 8 — exact minor-unit authority (migration V3); Double is a deprecated mirror.
    private Long amountMinor;
    private Long paidAmountMinor;

    @Deprecated
    private Double amount;

    private String currency;

    // PHASE 8 — keep the exact minor value and its deprecated Double mirror in sync.
    public void setAmountMinor(Long value) { this.amountMinor = value; this.amount = value == null ? null : PaymentMoney.toMajor(value); }
    public void setPaidAmountMinor(Long value) { this.paidAmountMinor = value; this.paidAmount = value == null ? null : PaymentMoney.toMajor(value); }
    public void setAmount(Double value) { this.amount = value; this.amountMinor = PaymentMoney.toMinor(value); }
    public void setPaidAmount(Double value) { this.paidAmount = value; this.paidAmountMinor = PaymentMoney.toMinor(value); }

    private String paymentStatus;

    private String paymentMethod;

    private String paymentTransactionId;

    @Deprecated
    private Double paidAmount;

    private LocalDateTime issuedAt;

    @PrePersist
    void onCreate() {
        if (issuedAt == null) issuedAt = LocalDateTime.now();
        if (amountMinor == null) amountMinor = PaymentMoney.toMinor(amount);
        if (paidAmountMinor == null) paidAmountMinor = PaymentMoney.toMinor(paidAmount);
    }
}
