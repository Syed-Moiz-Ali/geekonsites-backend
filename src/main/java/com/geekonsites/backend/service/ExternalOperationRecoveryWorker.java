package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.PaymentTransaction;
import com.geekonsites.backend.enums.PaymentReversalStatus;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.PaymentTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * PHASE 9 — bounded, DB-backed recovery for critical external side effects.
 *
 * <p>No message broker is used. A scheduled worker:
 * <ul>
 *   <li>selects a small batch of eligible operations with a row lock (so two app
 *       instances cannot reserve the same row);</li>
 *   <li>increments attempt counters, applies exponential backoff and caps attempts;</li>
 *   <li>commits the reservation <em>before</em> performing the provider call, so no lock
 *       is held during the network call;</li>
 *   <li>delegates to the same idempotent services used by the live flow (deterministic
 *       provider idempotency keys / existing-event lookups), so a retry never double-issues.</li>
 * </ul>
 * Disabled by default (dev/test); enabled in the {@code production} profile.
 */
@Component
public class ExternalOperationRecoveryWorker {

    private static final Logger log = LoggerFactory.getLogger(ExternalOperationRecoveryWorker.class);
    private static final long MAX_BACKOFF_SECONDS = 3600L;

    private final PaymentTransactionRepository paymentTransactions;
    private final BookingRepository bookings;
    private final ExcessReversalService excessReversalService;
    private final RemoteSessionProvisioningService remoteSessionProvisioningService;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.recovery.enabled:false}")
    private boolean enabled;

    @Value("${app.recovery.batch-size:20}")
    private int batchSize = 20;

    @Value("${app.recovery.max-attempts:8}")
    private int maxAttempts = 8;

    @Value("${app.recovery.base-backoff-seconds:60}")
    private long baseBackoffSeconds = 60;

    public ExternalOperationRecoveryWorker(
            PaymentTransactionRepository paymentTransactions,
            BookingRepository bookings,
            ExcessReversalService excessReversalService,
            RemoteSessionProvisioningService remoteSessionProvisioningService,
            PlatformTransactionManager transactionManager) {
        this.paymentTransactions = paymentTransactions;
        this.bookings = bookings;
        this.excessReversalService = excessReversalService;
        this.remoteSessionProvisioningService = remoteSessionProvisioningService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.recovery.fixed-delay-ms:60000}",
            initialDelayString = "${app.recovery.initial-delay-ms:30000}")
    public void run() {
        if (!enabled) {
            return;
        }
        int reversals = recoverExcessReversals();
        int remote = recoverRemoteProvisioning();
        if (reversals > 0 || remote > 0) {
            log.info("Recovery worker processed excessReversals={} remoteProvisioning={}", reversals, remote);
        }
    }

    int recoverExcessReversals() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> ids = transactionTemplate.execute(status -> reserveExcessReversals(now));
        if (ids == null) {
            return 0;
        }
        for (Long id : ids) {
            try {
                excessReversalService.reverse(id);
            } catch (RuntimeException exception) {
                log.warn("Excess reversal recovery failed for paymentTransactionId={}", id, exception);
            }
        }
        return ids.size();
    }

    int recoverRemoteProvisioning() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> ids = transactionTemplate.execute(status -> reserveRemoteProvisioning(now));
        if (ids == null) {
            return 0;
        }
        for (Long id : ids) {
            try {
                remoteSessionProvisioningService.provisionAfterPayment(id);
            } catch (RuntimeException exception) {
                log.warn("Remote provisioning recovery failed for bookingId={}", id, exception);
            }
        }
        return ids.size();
    }

    private List<Long> reserveExcessReversals(LocalDateTime now) {
        List<PaymentTransaction> candidates = paymentTransactions.findReversalRecoveryCandidates(
                EnumSet.of(PaymentReversalStatus.PENDING, PaymentReversalStatus.FAILED),
                maxAttempts, now, PageRequest.of(0, batchSize));
        List<Long> queue = new ArrayList<>();
        for (PaymentTransaction transaction : candidates) {
            int attempts = transaction.getReversalAttempts() + 1;
            transaction.setReversalAttempts(attempts);
            transaction.setReversalAttemptedAt(now);
            transaction.setReversalNextAttemptAt(now.plusSeconds(backoffSeconds(attempts)));
            if (attempts >= maxAttempts) {
                transaction.setReversalStatus(PaymentReversalStatus.FAILED);
                transaction.setReversalError("Automatic recovery exhausted; manual review required");
                log.warn("Excess reversal exhausted automatic retries for paymentTransactionId={}", transaction.getId());
            } else {
                transaction.setReversalStatus(PaymentReversalStatus.PENDING);
                queue.add(transaction.getId());
            }
            paymentTransactions.save(transaction);
        }
        return queue;
    }

    private List<Long> reserveRemoteProvisioning(LocalDateTime now) {
        List<Booking> candidates = bookings.findRemoteProvisioningRecoveryCandidates(
                List.of("FAILED", "PROVISIONING"), maxAttempts, now, PageRequest.of(0, batchSize));
        List<Long> queue = new ArrayList<>();
        for (Booking booking : candidates) {
            int attempts = booking.getRemoteProvisioningAttempts() + 1;
            booking.setRemoteProvisioningAttempts(attempts);
            booking.setRemoteProvisioningNextAttemptAt(now.plusSeconds(backoffSeconds(attempts)));
            if (attempts >= maxAttempts) {
                booking.setRemoteSessionStatus("FAILED");
                booking.setRemoteSessionProvisioningError("Automatic recovery exhausted; manual review required");
                log.warn("Remote provisioning exhausted automatic retries for bookingId={}", booking.getId());
            } else {
                queue.add(booking.getId());
            }
            bookings.save(booking);
        }
        return queue;
    }

    private long backoffSeconds(int attempts) {
        long value = baseBackoffSeconds;
        for (int i = 1; i < attempts && value < MAX_BACKOFF_SECONDS; i++) {
            value *= 2;
        }
        return Math.min(value, MAX_BACKOFF_SECONDS);
    }
}
