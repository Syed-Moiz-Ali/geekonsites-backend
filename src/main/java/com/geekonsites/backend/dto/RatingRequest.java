package com.geekonsites.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RatingRequest {

    @NotNull(message = "bookingId is required")
    private Long bookingId;

    // Derived from the authenticated customer + booking; accepted for compatibility but ignored.
    private Long customerId;

    private Long technicianId;

    @NotNull(message = "rating is required")
    @Min(value = 1, message = "rating must be between 1 and 5")
    @Max(value = 5, message = "rating must be between 1 and 5")
    private Integer rating;

    @Size(max = 2000, message = "review must be 2000 characters or fewer")
    private String review;
}
