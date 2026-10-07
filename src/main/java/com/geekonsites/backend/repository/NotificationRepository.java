package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository
        extends JpaRepository<Notification, Long> {

    List<Notification> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    Optional<Notification> findByIdAndCustomerId(Long id, Long customerId);
    Optional<Notification> findByIdAndTechnicianId(Long id, Long technicianId);
    Optional<Notification> findByIdAndAgentId(Long id, Long agentId);
    boolean existsByIdempotencyKey(String idempotencyKey);

    List<Notification> findByTechnicianIdOrderByCreatedAtDesc(Long technicianId);

    List<Notification> findByAgentIdOrderByCreatedAtDesc(Long agentId);

    List<Notification> findByAdminIdOrderByCreatedAtDesc(Long adminId);

    // PHASE 9 — DB-side, bounded notification lists.
    Page<Notification> findByCustomerId(Long customerId, Pageable pageable);
    Page<Notification> findByCustomerIdAndIsRead(Long customerId, Boolean isRead, Pageable pageable);
    Page<Notification> findByTechnicianId(Long technicianId, Pageable pageable);
    Page<Notification> findByTechnicianIdAndIsRead(Long technicianId, Boolean isRead, Pageable pageable);
    Page<Notification> findByAgentId(Long agentId, Pageable pageable);
    Page<Notification> findByAgentIdAndIsRead(Long agentId, Boolean isRead, Pageable pageable);
    Page<Notification> findByAdminId(Long adminId, Pageable pageable);

    // PHASE 9 — bulk mark-all (no full list load).
    @Modifying
    @Query("update Notification n set n.isRead = true where n.customerId = :id and n.isRead = false")
    int markAllCustomerRead(@Param("id") Long customerId);

    @Modifying
    @Query("update Notification n set n.isRead = true where n.technicianId = :id and n.isRead = false")
    int markAllTechnicianRead(@Param("id") Long technicianId);

    @Modifying
    @Query("update Notification n set n.isRead = true where n.agentId = :id and n.isRead = false")
    int markAllAgentRead(@Param("id") Long agentId);
}
