package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.PaymentTransaction;
import com.geekonsites.backend.enums.PaymentProvider;
import com.geekonsites.backend.enums.PaymentReversalStatus;
import com.geekonsites.backend.enums.PaymentTransactionStatus;
import com.geekonsites.backend.enums.PaymentType;
import com.geekonsites.backend.repository.PaymentTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PHASE 4 — excess-capture technical reversal: deterministic, persisted, idempotent.
 */
class ExcessReversalServiceTest {

    private PaymentTransactionRepository transactions;
    private StripeRefundGateway gateway;
    private ExcessReversalService service;

    @BeforeEach
    void setUp() {
        transactions = mock(PaymentTransactionRepository.class);
        gateway = mock(StripeRefundGateway.class);
        // PlatformTransactionManager mock: TransactionTemplate runs the action synchronously.
        service = new ExcessReversalService(transactions, gateway, mock(PlatformTransactionManager.class));
        when(transactions.save(any(PaymentTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private PaymentTransaction excessTransaction() {
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setId(5L);
        transaction.setBookingId(1L);
        transaction.setPaymentType(PaymentType.FULL);
        transaction.setProvider(PaymentProvider.STRIPE);
        transaction.setAmountMinor(10000L);
        transaction.setCurrency("USD");
        transaction.setStatus(PaymentTransactionStatus.SUCCEEDED);
        transaction.setExcess(true);
        transaction.setReversalStatus(PaymentReversalStatus.PENDING);
        transaction.setPaymentIntentId("pi_excess");
        when(transactions.findByIdForUpdate(5L)).thenReturn(Optional.of(transaction));
        return transaction;
    }

    @Test
    void reversesExcessCaptureWithDeterministicKey() {
        PaymentTransaction transaction = excessTransaction();
        when(gateway.refundPaymentTransaction(any(), eq(10000L), eq("gos-excess-reversal-5")))
                .thenReturn(new StripeRefundGateway.StripeRefundResult("pi_excess", "re_excess", "succeeded"));

        service.reverse(5L);

        assertEquals(PaymentReversalStatus.SUCCEEDED, transaction.getReversalStatus());
        assertEquals("re_excess", transaction.getReversalRefundId());
        verify(gateway, times(1)).refundPaymentTransaction(any(), eq(10000L), eq("gos-excess-reversal-5"));
    }

    @Test
    void secondReversalIsIdempotent() {
        PaymentTransaction transaction = excessTransaction();
        transaction.setReversalStatus(PaymentReversalStatus.SUCCEEDED);
        transaction.setReversalRefundId("re_already");

        service.reverse(5L);

        verify(gateway, never()).refundPaymentTransaction(any(), anyLong(), anyString());
    }

    @Test
    void providerFailureLeavesRetryableFailedState() {
        PaymentTransaction transaction = excessTransaction();
        when(gateway.refundPaymentTransaction(any(), anyLong(), anyString()))
                .thenThrow(new RuntimeException("Stripe unavailable"));

        service.reverse(5L);

        assertEquals(PaymentReversalStatus.FAILED, transaction.getReversalStatus());
        assertNotNull(transaction.getReversalError());
        // The excess capture remains excluded/marked; nothing marks it as normal settlement.
        assertEquals(true, transaction.isExcess());
    }
}
