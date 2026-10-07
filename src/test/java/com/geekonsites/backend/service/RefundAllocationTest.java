package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.AdminRefundDecisionDto;
import com.geekonsites.backend.dto.RefundRequestCreateDto;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.PaymentTransaction;
import com.geekonsites.backend.entity.RefundRequest;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.PaymentType;
import com.geekonsites.backend.enums.RefundStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.PaymentRefundRepository;
import com.geekonsites.backend.repository.PaymentTransactionRepository;
import com.geekonsites.backend.repository.RefundRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PHASE 1 — multi-transaction refund allocation (Step 25 K–O of the phase brief).
 *
 * <p>Uses a mocked repository layer plus a recording {@link StripeRefundGateway} so the
 * allocation strategy is verified exactly: most-recent capture first, never exceeding
 * each transaction's remaining captured amount, idempotent on retry.
 */
class RefundAllocationTest {

    private RefundRequestRepository refunds;
    private BookingRepository bookings;
    private StripeRefundGateway gateway;
    private EmailService email;
    private PaymentTransactionRepository transactions;
    private PaymentRefundRepository paymentRefunds;
    private RefundService service;

    @BeforeEach
    void setUp() {
        refunds = mock(RefundRequestRepository.class);
        bookings = mock(BookingRepository.class);
        gateway = mock(StripeRefundGateway.class);
        email = mock(EmailService.class);
        transactions = mock(PaymentTransactionRepository.class);
        paymentRefunds = mock(PaymentRefundRepository.class);
        service = new RefundService(refunds, bookings, new RefundRuleEngine(), gateway, email, transactions, paymentRefunds,
                new PaymentRefundStateMachine(), testTransactionManager());

        when(refunds.save(any())).thenAnswer(call -> call.getArgument(0));
        when(refunds.findById(10L)).thenAnswer(call ->
                Optional.ofNullable(PENDING_REFUND.get()));
        when(refunds.findAllByOrderByRequestedAtDesc()).thenReturn(List.of());
        when(bookings.findById(1L)).thenAnswer(call -> Optional.ofNullable(BOOKING.get()));

        when(transactions.findByBookingIdAndStatusOrderByCreatedAtAsc(eq(1L), any()))
                .thenAnswer(call -> TRANSACTIONS.get());

        when(paymentRefunds.sumSucceededAmountMinorByRefundRequestId(anyLong())).thenReturn(0L);
        when(paymentRefunds.sumActiveAmountMinorByPaymentTransactionId(anyLong())).thenReturn(0L);
        when(paymentRefunds.findByRefundRequestIdAndPaymentTransactionId(anyLong(), anyLong()))
                .thenReturn(Optional.empty());
        when(refunds.findByIdForUpdate(anyLong())).thenAnswer(call -> refunds.findById(call.getArgument(0)));
        when(paymentRefunds.save(any())).thenAnswer(call -> call.getArgument(0));
        when(gateway.refundPaymentTransaction(any(PaymentTransaction.class), anyLong(), anyString()))
                .thenAnswer(call -> {
                    PaymentTransaction t = call.getArgument(0);
                    long amount = call.getArgument(1);
                    return new StripeRefundGateway.StripeRefundResult("pi_" + t.getId(), "re_" + t.getId() + "_" + amount, "succeeded");
                });
    }

    private final ThreadLocal<RefundRequest> PENDING_REFUND = new ThreadLocal<>();
    private final ThreadLocal<Booking> BOOKING = new ThreadLocal<>();
    private final ThreadLocal<List<PaymentTransaction>> TRANSACTIONS = new ThreadLocal<>();

    private void givenSplitBooking() {
        Booking booking = new Booking();
        booking.setId(1L);
        booking.setCustomerId(7L);
        booking.setCustomerEmail("customer@example.com");
        booking.setCountry("US");
        booking.setCurrency("USD");
        booking.setPaidAmount(100.0);
        BOOKING.set(booking);

        // Ascending created order: ADVANCE (older) then REMAINING (newer).
        TRANSACTIONS.set(List.of(
                transaction(101L, PaymentType.ADVANCE, 3000L),
                transaction(102L, PaymentType.REMAINING, 7000L)));

        RefundRequest refund = new RefundRequest();
        refund.setId(10L);
        refund.setBookingId(1L);
        refund.setCustomerId(7L);
        refund.setCurrency("USD");
        refund.setCountry("US");
        refund.setRefundStatus(RefundStatus.REQUESTED);
        refund.setSuggestedMaximumRefundAmount(BigDecimal.valueOf(100).setScale(2));
        PENDING_REFUND.set(refund);
    }

    private PaymentTransaction transaction(Long id, PaymentType type, long amountMinor) {
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setId(id);
        transaction.setBookingId(1L);
        transaction.setPaymentType(type);
        transaction.setAmountMinor(amountMinor);
        transaction.setCurrency("USD");
        return transaction;
    }

    private org.springframework.transaction.PlatformTransactionManager testTransactionManager() {
        org.springframework.transaction.PlatformTransactionManager tm =
                mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        return tm;
    }

    private AdminRefundDecisionDto decision(String amount) {
        AdminRefundDecisionDto dto = new AdminRefundDecisionDto();
        dto.setAmount(new BigDecimal(amount));
        return dto;
    }

    private User admin() {
        User user = new User();
        user.setId(99L);
        user.setRole(Role.ADMIN);
        return user;
    }

    // K — a single captured payment is refunded in full.
    @Test
    void fullRefundOfSinglePaymentSucceeds() {
        givenSplitBooking();
        TRANSACTIONS.set(List.of(transaction(101L, PaymentType.FULL, 10000L)));

        RefundRequest result = service.approveAndExecute(10L, decision("100.00"), admin());

        assertEquals(RefundStatus.REFUNDED, result.getRefundStatus());
        verify(gateway).refundPaymentTransaction(any(PaymentTransaction.class), eq(10000L), anyString());
    }

    // L — a full 100 refund is allocated across both captures (70 then 30).
    @Test
    void splitFullRefundAllocatesAcrossBothTransactions() {
        givenSplitBooking();

        RefundRequest result = service.approveAndExecute(10L, decision("100.00"), admin());

        assertEquals(RefundStatus.REFUNDED, result.getRefundStatus());
        verify(gateway).refundPaymentTransaction(any(PaymentTransaction.class), eq(7000L), anyString());
        verify(gateway).refundPaymentTransaction(any(PaymentTransaction.class), eq(3000L), anyString());
        verify(gateway, times(2)).refundPaymentTransaction(any(PaymentTransaction.class), anyLong(), anyString());
    }

    // M — an 80 partial refund is allocated 70 (newest) + 10 (older).
    @Test
    void splitPartialRefundAllocatesCorrectly() {
        givenSplitBooking();

        RefundRequest result = service.approveAndExecute(10L, decision("80.00"), admin());

        assertEquals(RefundStatus.PARTIALLY_REFUNDED, result.getRefundStatus());
        verify(gateway).refundPaymentTransaction(any(PaymentTransaction.class), eq(7000L), anyString());
        verify(gateway).refundPaymentTransaction(any(PaymentTransaction.class), eq(1000L), anyString());
    }

    // N — retrying a refund that already executed one allocation does not double-refund.
    @Test
    void refundRetryIsIdempotent() {
        givenSplitBooking();
        // The newest transaction was already refunded 7000 in a previous attempt.
        when(paymentRefunds.sumSucceededAmountMinorByRefundRequestId(10L)).thenReturn(7000L);
        when(paymentRefunds.sumActiveAmountMinorByPaymentTransactionId(102L)).thenReturn(7000L);
        when(paymentRefunds.sumActiveAmountMinorByPaymentTransactionId(101L)).thenReturn(0L);

        RefundRequest result = service.approveAndExecute(10L, decision("100.00"), admin());

        assertEquals(RefundStatus.REFUNDED, result.getRefundStatus());
        verify(gateway, times(1)).refundPaymentTransaction(any(PaymentTransaction.class), anyLong(), anyString());
        verify(gateway).refundPaymentTransaction(any(PaymentTransaction.class), eq(3000L), anyString());
    }

    // O — an approved amount exceeding what was actually captured cannot be allocated.
    @Test
    void refundAboveCapturedTotalIsRejectedAndMarkedFailed() {
        givenSplitBooking();
        TRANSACTIONS.set(List.of(transaction(101L, PaymentType.ADVANCE, 7000L)));

        assertThrows(RuntimeException.class, () -> service.approveAndExecute(10L, decision("100.00"), admin()));

        assertEquals(RefundStatus.FAILED, PENDING_REFUND.get().getRefundStatus());
        verify(refunds, atLeastOnce()).save(any());
    }
}
