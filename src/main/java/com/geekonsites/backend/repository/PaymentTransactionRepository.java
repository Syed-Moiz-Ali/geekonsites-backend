package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.PaymentTransaction;
import com.geekonsites.backend.enums.PaymentReversalStatus;
import com.geekonsites.backend.enums.PaymentTransactionStatus;
import com.geekonsites.backend.enums.PaymentType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * PHASE 1 — persistence for the authoritative payment ledger.
 *
 * <p>Queries are deliberately database-side (no in-memory filtering) so payment
 * history and refundable-amount derivation stay efficient as bookings accumulate
 * multiple transactions.
 */
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PaymentTransaction t where t.id = :id")
    Optional<PaymentTransaction> findByIdForUpdate(@Param("id") Long id);

    Optional<PaymentTransaction> findByCheckoutSessionId(String checkoutSessionId);

    Optional<PaymentTransaction> findByPaymentIntentId(String paymentIntentId);

    boolean existsByCheckoutSessionId(String checkoutSessionId);

    List<PaymentTransaction> findByBookingIdOrderByCreatedAtAsc(Long bookingId);

    List<PaymentTransaction> findByBookingIdAndStatusOrderByCreatedAtAsc(
            Long bookingId,
            PaymentTransactionStatus status
    );

    /** Latest still-active checkout attempt for a booking/payment purpose (Phase 3). */
    Optional<PaymentTransaction> findFirstByBookingIdAndPaymentTypeAndStatusInAndExcessFalseOrderByCreatedAtDesc(
            Long bookingId,
            PaymentType paymentType,
            Collection<PaymentTransactionStatus> statuses
    );

    @Query("""
            select coalesce(sum(t.amountMinor), 0)
            from PaymentTransaction t
            where t.bookingId = :bookingId
              and t.status = com.geekonsites.backend.enums.PaymentTransactionStatus.SUCCEEDED
              and t.excess = false
            """)
    long sumSuccessfulAmountMinorByBookingId(@Param("bookingId") Long bookingId);

    // PHASE 9 — bounded, row-locked recovery scan for failed/pending excess reversals.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select t from PaymentTransaction t
            where t.excess = true
              and t.reversalStatus in :statuses
              and t.reversalAttempts < :maxAttempts
              and (t.reversalNextAttemptAt is null or t.reversalNextAttemptAt <= :now)
            order by t.id asc
            """)
    List<PaymentTransaction> findReversalRecoveryCandidates(
            @Param("statuses") Collection<PaymentReversalStatus> statuses,
            @Param("maxAttempts") int maxAttempts,
            @Param("now") LocalDateTime now,
            Pageable pageable);

    long countByExcessTrueAndReversalStatusNot(PaymentReversalStatus status);

    List<PaymentTransaction> findByExcessTrueAndReversalStatusNotOrderByIdAsc(PaymentReversalStatus status, Pageable pageable);
}
