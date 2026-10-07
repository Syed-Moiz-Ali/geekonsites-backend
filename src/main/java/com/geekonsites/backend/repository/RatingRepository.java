package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.Rating;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RatingRepository
        extends JpaRepository<Rating, Long> {

    List<Rating> findByTechnicianId(Long technicianId);

    boolean existsByBookingId(Long bookingId);
}