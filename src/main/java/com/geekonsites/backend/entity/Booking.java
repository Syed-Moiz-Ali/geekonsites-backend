package com.geekonsites.backend.entity;

import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.service.PaymentMoney;
import jakarta.persistence.*;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "bookings")
@Data
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ==========================
    // Customer Details
    // ==========================

    private Long customerId;
    private String customerName;
    private String customerEmail;
    private String customerPhone;

    // ==========================
    // Technician Details
    // ==========================

    private Long technicianId;
    private String technicianName;
    private String technicianPhone;

    // NEW
    private LocalDateTime technicianAcceptedAt;
    private LocalDateTime technicianRejectedAt;
    private String technicianRejectReason;

    // ==========================
    // Agent Details
    // ==========================

    private Long agentId;
    private String agentName;

    // ==========================
    // Service
    // ==========================

    private String serviceType;

    // PHASE 6 — reference to the catalog + immutable historical snapshots.
    private Long serviceId;
    private String serviceCodeSnapshot;
    private String serviceNameSnapshot;
    private String serviceModeSnapshot;

    @Enumerated(EnumType.STRING)
    private ServiceMode serviceMode;

    @Column(columnDefinition = "TEXT")
    private String issueDescription;

    // ==========================
    // Address
    // ==========================

    private String address;
    private String city;
    private String state;
    private String country;
    private String postalCode;

    private LocalDate bookingDate;
    private String timeSlot;

    // ==========================
    // Pricing
    // ==========================

    private String currency;

    // PHASE 8 — authoritative exact minor-unit money (BIGINT columns added by
    // migration V3). The legacy Double fields below are retained, deprecated,
    // read-only mirrors; they are only ever written from these exact values.
    private Long baseAmountMinor;
    private Long addonsAmountMinor;
    private Long protectionAmountMinor;
    private Long platformFeeMinor;
    private Long totalAmountMinor;
    private Long advanceAmountMinor;
    private Long remainingAmountMinor;
    private Long paidAmountMinor;

    @Deprecated
    private Double baseAmount;
    @Deprecated
    private Double addonsAmount;
    @Deprecated
    private Double protectionAmount;
    @Deprecated
    private Double platformFee;
    @Deprecated
    private Double totalAmount;

    // ==========================
    // Payment
    // ==========================

    @Deprecated
    private Double advanceAmount;
    @Deprecated
    private Double remainingAmount;
    @Deprecated
    private Double paidAmount;

    // PHASE 8 — the exact minor value and its deprecated Double mirror are kept in
    // lock-step by these paired setters, so whichever representation a caller writes
    // (exact in new code, legacy Double in older code/tests) both stay consistent.
    public void setBaseAmountMinor(Long value) { this.baseAmountMinor = value; this.baseAmount = value == null ? null : PaymentMoney.toMajor(value); }
    public void setAddonsAmountMinor(Long value) { this.addonsAmountMinor = value; this.addonsAmount = value == null ? null : PaymentMoney.toMajor(value); }
    public void setProtectionAmountMinor(Long value) { this.protectionAmountMinor = value; this.protectionAmount = value == null ? null : PaymentMoney.toMajor(value); }
    public void setPlatformFeeMinor(Long value) { this.platformFeeMinor = value; this.platformFee = value == null ? null : PaymentMoney.toMajor(value); }
    public void setTotalAmountMinor(Long value) { this.totalAmountMinor = value; this.totalAmount = value == null ? null : PaymentMoney.toMajor(value); }
    public void setAdvanceAmountMinor(Long value) { this.advanceAmountMinor = value; this.advanceAmount = value == null ? null : PaymentMoney.toMajor(value); }
    public void setRemainingAmountMinor(Long value) { this.remainingAmountMinor = value; this.remainingAmount = value == null ? null : PaymentMoney.toMajor(value); }
    public void setPaidAmountMinor(Long value) { this.paidAmountMinor = value; this.paidAmount = value == null ? null : PaymentMoney.toMajor(value); }

    public void setBaseAmount(Double value) { this.baseAmount = value; this.baseAmountMinor = PaymentMoney.toMinor(value); }
    public void setAddonsAmount(Double value) { this.addonsAmount = value; this.addonsAmountMinor = PaymentMoney.toMinor(value); }
    public void setProtectionAmount(Double value) { this.protectionAmount = value; this.protectionAmountMinor = PaymentMoney.toMinor(value); }
    public void setPlatformFee(Double value) { this.platformFee = value; this.platformFeeMinor = PaymentMoney.toMinor(value); }
    public void setTotalAmount(Double value) { this.totalAmount = value; this.totalAmountMinor = PaymentMoney.toMinor(value); }
    public void setAdvanceAmount(Double value) { this.advanceAmount = value; this.advanceAmountMinor = PaymentMoney.toMinor(value); }
    public void setRemainingAmount(Double value) { this.remainingAmount = value; this.remainingAmountMinor = PaymentMoney.toMinor(value); }
    public void setPaidAmount(Double value) { this.paidAmount = value; this.paidAmountMinor = PaymentMoney.toMinor(value); }

    private String paymentType;
    private String paymentStatus;

    private String paymentTransactionId;
    private String paymentMethod;

    // UK statutory early-service consent (server-authored audit evidence)
    @Column(nullable = false, columnDefinition = "boolean default false")
    private Boolean ukEarlyServiceConsent = false;

    private LocalDateTime ukEarlyServiceConsentAt;

    private String ukEarlyServiceConsentTextVersion;

    // ==========================
    // Add-ons
    // ==========================

    @Column(length = 2000)
    private String selectedAddons;

    private String protectionPlan;

    // ==========================
    // Booking Status
    // ==========================

    @Enumerated(EnumType.STRING)
    private BookingStatus bookingStatus;

    // ==========================
    // Tracking
    // ==========================

    private Double customerLatitude;
    private Double customerLongitude;

    private Double technicianLatitude;
    private Double technicianLongitude;

    private Integer etaMinutes;

    // Live Tracking (Uber / Zomato)

private Double remainingDistanceKm;

private Double technicianHeading;

private Double technicianSpeed;

private Boolean trackingEnabled;

private Boolean technicianArrived;

private LocalDateTime lastLocationUpdate;

private LocalDateTime estimatedArrivalTime;

private String currentRoad;

private String liveTrackingStatus;

    // NEW
    private LocalDateTime technicianOnTheWayAt;
    private LocalDateTime serviceStartedAt;
    private LocalDateTime serviceCompletedAt;

    // ==========================
    // Remote Session
    // ==========================

    private Boolean remoteSessionRequired;

    private String remoteSessionLink;

    @JsonProperty("remoteSessionLink")
    public String getRemoteSessionLink() {
        return "PAID".equalsIgnoreCase(paymentStatus) ? remoteSessionLink : null;
    }

    private String remoteSessionStatus;

    private String googleCalendarEventId;

    private LocalDateTime remoteSessionScheduledStart;

    private LocalDateTime remoteSessionScheduledEnd;

    @Column(columnDefinition = "TEXT")
    private String remoteSessionProvisioningError;

    // PHASE 9 — bounded retry bookkeeping for scheduled remote-provisioning recovery.
    @Column(name = "remote_provisioning_attempts", nullable = false, columnDefinition = "integer default 0")
    private int remoteProvisioningAttempts = 0;

    @Column(name = "remote_provisioning_next_attempt_at")
    private LocalDateTime remoteProvisioningNextAttemptAt;

    private LocalDateTime remoteSessionStartedAt;
    private LocalDateTime remoteSessionEndedAt;

    // ==========================
    // Invoice
    // ==========================

    private String invoiceNumber;
    private Boolean invoiceGenerated;

    private LocalDateTime invoiceGeneratedAt;

    // ==========================
    // Customer Rating
    // ==========================

    private Integer customerRating;

    @Column(columnDefinition = "TEXT")
    private String customerReview;

    private LocalDateTime ratedAt;

    // ==========================
    // Booking Close
    // ==========================

    private Boolean bookingClosed;
    private LocalDateTime bookingClosedAt;

    // ==========================
    // Audit
    // ==========================

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {

        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();

        if (bookingStatus == null)
            bookingStatus = BookingStatus.PENDING;

        if (paymentStatus == null)
            paymentStatus = "PENDING";

        if (invoiceGenerated == null)
            invoiceGenerated = false;

        if (bookingClosed == null)
            bookingClosed = false;

        if (remoteSessionRequired == null)
            remoteSessionRequired = false;

        if (remoteSessionStatus == null && Boolean.TRUE.equals(remoteSessionRequired))
            remoteSessionStatus = "PAYMENT_PENDING";

        if (addonsAmount == null)
            addonsAmount = 0.0;

        if (protectionAmount == null)
            protectionAmount = 0.0;

        if (platformFee == null)
            platformFee = 0.0;

        if (baseAmount == null)
            baseAmount = 0.0;

        if (totalAmount == null)
            totalAmount = 0.0;

        if (advanceAmount == null)
            advanceAmount = 0.0;

        if (remainingAmount == null)
            remainingAmount = 0.0;

        if (paidAmount == null)
            paidAmount = 0.0;

        // PHASE 8 — exact minor-unit authority. If a caller (legacy code or an older
        // test) only populated the deprecated Double mirrors, derive the exact values
        // once so a persisted row is never left with a null financial authority.
        if (baseAmountMinor == null) baseAmountMinor = PaymentMoney.toMinor(baseAmount);
        if (addonsAmountMinor == null) addonsAmountMinor = PaymentMoney.toMinor(addonsAmount);
        if (protectionAmountMinor == null) protectionAmountMinor = PaymentMoney.toMinor(protectionAmount);
        if (platformFeeMinor == null) platformFeeMinor = PaymentMoney.toMinor(platformFee);
        if (totalAmountMinor == null) totalAmountMinor = PaymentMoney.toMinor(totalAmount);
        if (advanceAmountMinor == null) advanceAmountMinor = PaymentMoney.toMinor(advanceAmount);
        if (remainingAmountMinor == null) remainingAmountMinor = PaymentMoney.toMinor(remainingAmount);
        if (paidAmountMinor == null) paidAmountMinor = PaymentMoney.toMinor(paidAmount);

        if (ukEarlyServiceConsent == null)
            ukEarlyServiceConsent = false;

        if (trackingEnabled == null)
         trackingEnabled = false;

      if (technicianArrived == null)
        technicianArrived = false;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
