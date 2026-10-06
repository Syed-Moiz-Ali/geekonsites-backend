package com.geekonsites.backend.dto;

import com.geekonsites.backend.enums.ServiceMode;
import lombok.Data;

@Data
public class BookingRequest {

    // Customer Details
    private Long customerId;
    private String customerName;
    private String customerEmail;
    private String customerPhone;

    // Service Details
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
    private String bookingDate;
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