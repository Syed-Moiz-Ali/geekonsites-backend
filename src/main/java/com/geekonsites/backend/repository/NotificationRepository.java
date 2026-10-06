package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository
        extends JpaRepository<Notification, Long> {

    List<Notification> findByCustomerIdOrderByCreatedAtDesc(
            Long customerId
    );

    Optional<Notification> findByIdAndCustomerId(Long id, Long customerId);
    Optional<Notification> findByIdAndTechnicianId(Long id, Long technicianId);
    Optional<Notification> findByIdAndAgentId(Long id, Long agentId);
    boolean existsByIdempotencyKey(String idempotencyKey);

    List<Notification> findByTechnicianIdOrderByCreatedAtDesc(
            Long technicianId
    );

    List<Notification> findByAgentIdOrderByCreatedAtDesc(
            Long agentId
    );

    List<Notification> findByAdminIdOrderByCreatedAtDesc(
        Long adminId
);
}
