package com.geekonsites.backend.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TechnicianLocationRequest {

    // Current GPS
    private Double latitude;
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