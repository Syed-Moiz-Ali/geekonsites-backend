package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.RefundRequest;
import com.geekonsites.backend.enums.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {
    List<RefundRequest> findByCustomerIdOrderByRequestedAtDesc(Long customerId);
    List<RefundRequest> findAllByOrderByRequestedAtDesc();
    boolean existsByBookingIdAndRefundStatusIn(Long bookingId, Collection<RefundStatus> statuses);
    Optional<RefundRequest> findByStripeRefundId(String stripeRefundId);
}
