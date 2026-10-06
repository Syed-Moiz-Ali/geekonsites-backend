package com.geekonsites.backend.dto;

import lombok.Data;

@Data
public class RatingRequest {

    private Long bookingId;

    private Long customerId;

    private Long technicianId;

    private Integer rating;

    private String review;
}