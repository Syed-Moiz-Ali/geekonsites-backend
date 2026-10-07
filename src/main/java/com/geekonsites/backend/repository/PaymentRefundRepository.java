package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.PaymentRefund;
import com.geekonsites.backend.enums.PaymentRefundStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * PHASE 1 — persistence for the refund-execution ledger.
 */
public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentRefund r where r.id = :id")
    Optional<PaymentRefund> findByIdForUpdate(@Param("id") Long id);

    List<PaymentRefund> findByRefundRequestIdOrderByCreatedAtAsc(Long refundRequestId);

    List<PaymentRefund> findByPaymentTransactionId(Long paymentTransactionId);

    Optional<PaymentRefund> findByProviderRefundId(String providerRefundId);

    Optional<PaymentRefund> findByRefundRequestIdAndPaymentTransactionId(
            Long refundRequestId,
            Long paymentTransactionId
    );

    @Query("""
            select coalesce(sum(r.amountMinor), 0)
            from PaymentRefund r
            where r.paymentTransactionId = :transactionId
              and r.status = com.geekonsites.backend.enums.PaymentRefundStatus.SUCCEEDED
            """)
    long sumSucceededAmountMinorByPaymentTransactionId(@Param("transactionId") Long transactionId);

    @Query("""
            select coalesce(sum(r.amountMinor), 0)
            from PaymentRefund r
            where r.refundRequestId = :refundRequestId
              and r.status = com.geekonsites.backend.enums.PaymentRefundStatus.SUCCEEDED
            """)
    long sumSucceededAmountMinorByRefundRequestId(@Param("refundRequestId") Long refundRequestId);

    /**
     * PHASE 4 — reserved capacity: PENDING allocations already consume refundable
     * capacity so a concurrent worker cannot over-allocate the same capture.
     */
    @Query("""
            select coalesce(sum(r.amountMinor), 0)
            from PaymentRefund r
            where r.paymentTransactionId = :transactionId
              and r.status in (com.geekonsites.backend.enums.PaymentRefundStatus.PENDING,
                               com.geekonsites.backend.enums.PaymentRefundStatus.SUCCEEDED)
            """)
    long sumActiveAmountMinorByPaymentTransactionId(@Param("transactionId") Long transactionId);

    long countByStatus(PaymentRefundStatus status);
}
