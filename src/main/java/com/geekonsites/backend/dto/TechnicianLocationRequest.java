package com.geekonsites.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TechnicianLocationRequest {

    // Current GPS
    @DecimalMin(value = "-90.0", message = "latitude must be between -90 and 90")
    @DecimalMax(value = "90.0", message = "latitude must be between -90 and 90")
    private Double latitude;

    @DecimalMin(value = "-180.0", message = "longitude must be between -180 and 180")
    @DecimalMax(value = "180.0", message = "longitude must be between -180 and 180")
    private Double longitude;

    // Live ETA
    private Integer etaMinutes;

    // Uber / Zomato Tracking
    private Double remainingDistanceKm;
    private Double speed;
    private Double heading;

    // GPS Accuracy (meters)
    private Double accuracy;

    // Road Name
    private String currentRoad;

    // LIVE STATUS
    // ON_THE_WAY
    // ARRIVING
    // ARRIVED
    private String liveTrackingStatus;

    // GPS Timestamp
    private LocalDateTime gpsTime;
}
