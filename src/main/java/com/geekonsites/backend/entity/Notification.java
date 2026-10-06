package com.geekonsites.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "notifications")
@Data
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long customerId;

    private Long technicianId;

    private Long agentId;

    private Long adminId;

    private String recipientRole;

    private String title;

    private String message;

    private Long bookingId;

    private String type;

    private String actionUrl;

    @Column(unique = true)
    private String idempotencyKey;

    private Boolean isRead;

    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        if (isRead == null) {
            isRead = false;
        }

        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
