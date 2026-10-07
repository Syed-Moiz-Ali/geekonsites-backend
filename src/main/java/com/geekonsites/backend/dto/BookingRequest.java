package com.geekonsites.backend.dto;

import com.geekonsites.backend.enums.ServiceMode;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BookingRequest {

    // Customer Details
    private Long customerId;
    private String customerName;
    private String customerEmail;
    private String customerPhone;

    // Service Details
    // PHASE 6: serviceCode is the authoritative identifier (stable catalog code). serviceType
    // is retained only as a legacy compatibility alias resolved to a catalog code/name.
    private String serviceCode;
    private String serviceType;
    private ServiceMode serviceMode;
    private String issueDescription;

    // Address
    private String address;
    private String city;
    private String state;
    private String country;
    private String postalCode;

    // Schedule
    // PHASE 7: format validated declaratively; the actual calendar date is parsed safely
    // in the service (a format-valid but impossible date still yields 400, never 500).
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "bookingDate must be a valid date in YYYY-MM-DD format")
    private String bookingDate;
    @Size(max = 40, message = "timeSlot must be 40 characters or fewer")
    private String timeSlot;

    // Currency
    private String currency;

    // Dynamic Pricing
    private Double baseAmount;
    private Double addonsAmount;
    private Double protectionAmount;
    private Double platformFee;
    private Double totalAmount;

    // Payment Split
    private Double advanceAmount;
    private Double remainingAmount;
    private Double paidAmount;

    private String paymentType;
    private String paymentStatus;
    private String paymentMethod;
    private String paymentTransactionId;

    // Add-ons / Protection
    private String selectedAddons;
    private String protectionPlan;

    // Remote Session
    private Boolean remoteSessionRequired;
    private String remoteSessionLink;

    // Location
    private Double customerLatitude;
    private Double customerLongitude;
}