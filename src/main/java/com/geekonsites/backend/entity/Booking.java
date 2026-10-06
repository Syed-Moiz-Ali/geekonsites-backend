package com.geekonsites.backend.entity;

import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.ServiceMode;
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

    private Double baseAmount;
    private Double addonsAmount;
    private Double protectionAmount;
    private Double platformFee;
    private Double totalAmount;

    // ==========================
    // Payment
    // ==========================

    private Double advanceAmount;
    private Double remainingAmount;
    private Double paidAmount;

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
