package com.geekonsites.backend.dto;

import lombok.Data;

@Data
public class InvoiceRequest {

    private Long bookingId;

    private Long customerId;

    private Long technicianId;

    private String customerName;

    private String technicianName;

    private String serviceType;

    private Double amount;

    private String currency;
}