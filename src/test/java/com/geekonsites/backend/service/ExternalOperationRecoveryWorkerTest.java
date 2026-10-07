package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.PaymentTransaction;
import com.geekonsites.backend.enums.PaymentReversalStatus;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.PaymentTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** PHASE 9 — bounded, capped, row-locked recovery behaviour. */
class ExternalOperationRecoveryWorkerTest {

    private PaymentTransactionRepository paymentTransactions;
    private BookingRepository bookings;
    private ExcessReversalService excessReversalService;
    private RemoteSessionProvisioningService remoteProvisioningService;
    private ExternalOperationRecoveryWorker worker;

    @BeforeEach
    void setUp() {
        paymentTransactions = mock(PaymentTransactionRepository.class);
        bookings = mock(BookingRepository.class);
        excessReversalService = mock(ExcessReversalService.class);
        remoteProvisioningService = mock(RemoteSessionProvisioningService.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        worker = new ExternalOperationRecoveryWorker(
                paymentTransactions, bookings, excessReversalService, remoteProvisioningService, tm);
        when(paymentTransactions.findReversalRecoveryCandidates(anyCollection(), anyInt(), any(), any(Pageable.class)))
                .thenReturn(List.of());
        when(bookings.findRemoteProvisioningRecoveryCandidates(anyCollection(), anyInt(), any(), any(Pageable.class)))
                .thenReturn(List.of());
    }

    @Test
    void failedExcessReversalIsRetriedThroughTheIdempotentService() {
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setId(5L);
        transaction.setExcess(true);
        transaction.setReversalStatus(PaymentReversalStatus.FAILED);
        transaction.setReversalAttempts(0);
        when(paymentTransactions.findReversalRecoveryCandidates(anyCollection(), anyInt(), any(), any(Pageable.class)))
                .thenReturn(List.of(transaction));

        int processed = worker.recoverExcessReversals();

        assertTrue(processed == 1);
        verify(excessReversalService).reverse(5L);
        assertTrue(transaction.getReversalAttempts() == 1);
        assertTrue(transaction.getReversalNextAttemptAt() != null);
    }

    @Test
    void exhaustedExcessReversalIsParkedForManualReviewAndNotRetried() {
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setId(6L);
        transaction.setExcess(true);
        transaction.setReversalStatus(PaymentReversalStatus.FAILED);
        transaction.setReversalAttempts(8);
        when(paymentTransactions.findReversalRecoveryCandidates(anyCollection(), anyInt(), any(), any(Pageable.class)))
                .thenReturn(List.of(transaction));

        int processed = worker.recoverExcessReversals();

        assertTrue(processed == 0);
        verify(excessReversalService, never()).reverse(anyLong());
        assertTrue("Automatic recovery exhausted; manual review required".equals(transaction.getReversalError()));
    }

    @Test
    void failedRemoteProvisioningIsRetriedThroughTheIdempotentService() {
        Booking booking = new Booking();
        booking.setId(9L);
        booking.setRemoteSessionRequired(true);
        booking.setRemoteSessionStatus("FAILED");
        booking.setRemoteProvisioningAttempts(1);
        when(bookings.findRemoteProvisioningRecoveryCandidates(anyCollection(), anyInt(), any(), any(Pageable.class)))
                .thenReturn(List.of(booking));

        int processed = worker.recoverRemoteProvisioning();

        assertTrue(processed == 1);
        verify(remoteProvisioningService).provisionAfterPayment(9L);
    }

    @Test
    void recoveryScansAreBoundedByBatchSize() {
        worker.recoverExcessReversals();
        worker.recoverRemoteProvisioning();
        verify(paymentTransactions).findReversalRecoveryCandidates(anyCollection(), eq(8), any(),
                argThat(pageable -> pageable.getPageSize() == 20));
        verify(bookings).findRemoteProvisioningRecoveryCandidates(anyCollection(), eq(8), any(),
                argThat(pageable -> pageable.getPageSize() == 20));
    }
}
