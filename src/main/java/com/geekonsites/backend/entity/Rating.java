package com.geekonsites.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "ratings", uniqueConstraints =
        @UniqueConstraint(name = "uq_ratings_booking", columnNames = "booking_id"))
@Data
public class Rating {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PHASE 5 — one review per booking, enforced by DB uniqueness. */
    @Column(name = "booking_id", nullable = false, unique = true)
    private Long bookingId;

    private Long customerId;

    private Long technicianId;

    private Integer rating;

    private String review;
}