package com.geekonsites.backend.dto;

import lombok.Data;

@Data
public class TechnicianRatingResponse {

    private Long technicianId;

    private Double averageRating;

    private Integer totalReviews;
}