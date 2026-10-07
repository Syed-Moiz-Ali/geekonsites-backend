package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.RefundRequest;
import com.geekonsites.backend.enums.RefundStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {
    List<RefundRequest> findByCustomerIdOrderByRequestedAtDesc(Long customerId);
    List<RefundRequest> findAllByOrderByRequestedAtDesc();

    // PHASE 9 — paginated refund lists.
    Page<RefundRequest> findByCustomerIdOrderByRequestedAtDesc(Long customerId, Pageable pageable);
    Page<RefundRequest> findAllByOrderByRequestedAtDesc(Pageable pageable);
    boolean existsByBookingIdAndRefundStatusIn(Long bookingId, Collection<RefundStatus> statuses);
    Optional<RefundRequest> findByStripeRefundId(String stripeRefundId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefundRequest r where r.id = :id")
    Optional<RefundRequest> findByIdForUpdate(@Param("id") Long id);
}
