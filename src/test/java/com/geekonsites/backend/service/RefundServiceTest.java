package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.AdminRefundDecisionDto;
import com.geekonsites.backend.dto.RefundRequestCreateDto;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.RefundRequest;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.RefundStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.RefundRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RefundServiceTest {
    private RefundRequestRepository refunds;
    private BookingRepository bookings;
    private StripeRefundGateway stripe;
    private EmailService email;
    private RefundService service;

    @BeforeEach
    void setup() {
        refunds = mock(RefundRequestRepository.class);
        bookings = mock(BookingRepository.class);
        stripe = mock(StripeRefundGateway.class);
        email = mock(EmailService.class);
        service = new RefundService(refunds, bookings, new RefundRuleEngine(), stripe, email);
        when(refunds.save(any())).thenAnswer(call -> { RefundRequest value = call.getArgument(0); if (value.getId() == null) value.setId(10L); return value; });
        when(refunds.findAllByOrderByRequestedAtDesc()).thenReturn(List.of());
    }

    @Test
    void customerRequestUsesBookingCountryCurrencyAndPaidAmount() {
        Booking booking = booking(1L, 7L, "UK", "GBP", 121.0);
        when(bookings.findById(1L)).thenReturn(Optional.of(booking));
        RefundRequestCreateDto input = input();

        RefundRequest saved = service.requestRefund(1L, input, user(7L, Role.CUSTOMER));

        assertEquals("UK", saved.getCountry());
        assertEquals("GBP", saved.getCurrency());
        assertEquals(new BigDecimal("121.00"), saved.getRequestedRefundAmount());
        assertEquals(RefundStatus.REQUESTED, saved.getRefundStatus());
    }

    @Test
    void customerCannotRequestAgainstAnotherCustomersBooking() {
        when(bookings.findById(1L)).thenReturn(Optional.of(booking(1L, 7L, "US", "USD", 100)));
        assertThrows(RuntimeException.class, () -> service.requestRefund(1L, input(), user(8L, Role.CUSTOMER)));
        verify(refunds, never()).save(any());
    }

    @Test
    void duplicateActiveRefundIsPrevented() {
        when(bookings.findById(1L)).thenReturn(Optional.of(booking(1L, 7L, "US", "USD", 100)));
        when(refunds.existsByBookingIdAndRefundStatusIn(eq(1L), anyCollection())).thenReturn(true);
        assertThrows(RuntimeException.class, () -> service.requestRefund(1L, input(), user(7L, Role.CUSTOMER)));
    }

    @Test
    void nonAdminCannotExecuteRefund() {
        assertThrows(RuntimeException.class, () -> service.approveAndExecute(10L, decision("50.00"), user(7L, Role.CUSTOMER)));
        verifyNoInteractions(stripe);
    }

    @Test
    void overRefundIsRejectedBeforeStripe() {
        RefundRequest refund = refund(100);
        when(refunds.findById(10L)).thenReturn(Optional.of(refund));
        when(bookings.findById(1L)).thenReturn(Optional.of(booking(1L, 7L, "US", "USD", 100)));
        assertThrows(RuntimeException.class, () -> service.approveAndExecute(10L, decision("100.01"), user(99L, Role.ADMIN)));
        verifyNoInteractions(stripe);
    }

    @Test
    void fullAndPartialRefundStatusesComeOnlyFromStripeSuccess() {
        RefundRequest full = refund(100);
        when(refunds.findById(10L)).thenReturn(Optional.of(full));
        when(bookings.findById(1L)).thenReturn(Optional.of(booking(1L, 7L, "US", "USD", 100)));
        when(stripe.refund(any(), eq(new BigDecimal("100.00")), anyString())).thenReturn(new StripeRefundGateway.StripeRefundResult("pi_1", "re_full", "succeeded"));
        assertEquals(RefundStatus.REFUNDED, service.approveAndExecute(10L, decision("100.00"), user(99L, Role.ADMIN)).getRefundStatus());

        RefundRequest partial = refund(100);
        partial.setId(11L);
        when(refunds.findById(11L)).thenReturn(Optional.of(partial));
        when(stripe.refund(any(), eq(new BigDecimal("40.00")), anyString())).thenReturn(new StripeRefundGateway.StripeRefundResult("pi_1", "re_partial", "succeeded"));
        assertEquals(RefundStatus.PARTIALLY_REFUNDED, service.approveAndExecute(11L, decision("40.00"), user(99L, Role.ADMIN)).getRefundStatus());
    }

    @Test
    void stripeFailureLeavesAuditableFailedStatus() {
        RefundRequest refund = refund(100);
        when(refunds.findById(10L)).thenReturn(Optional.of(refund));
        when(bookings.findById(1L)).thenReturn(Optional.of(booking(1L, 7L, "US", "USD", 100)));
        when(stripe.refund(any(), any(), anyString())).thenThrow(new RuntimeException("Stripe unavailable"));
        assertThrows(RuntimeException.class, () -> service.approveAndExecute(10L, decision("50.00"), user(99L, Role.ADMIN)));
        assertEquals(RefundStatus.FAILED, refund.getRefundStatus());
        assertNotNull(refund.getFailureReason());
        verify(refunds, atLeast(2)).save(refund);
    }

    private RefundRequest refund(double maximum) {
        RefundRequest value = new RefundRequest(); value.setId(10L); value.setBookingId(1L); value.setCustomerId(7L); value.setCurrency("USD"); value.setCountry("US"); value.setRefundStatus(RefundStatus.REQUESTED); value.setSuggestedMaximumRefundAmount(BigDecimal.valueOf(maximum).setScale(2)); return value;
    }
    private RefundRequestCreateDto input() { RefundRequestCreateDto value = new RefundRequestCreateDto(); value.setReason("Service no longer required"); return value; }
    private AdminRefundDecisionDto decision(String amount) { AdminRefundDecisionDto value = new AdminRefundDecisionDto(); value.setAmount(new BigDecimal(amount)); value.setAdminNote("Reviewed evidence"); return value; }
    private User user(Long id, Role role) { User value = new User(); value.setId(id); value.setRole(role); value.setEmail(role.name().toLowerCase() + "@example.com"); return value; }
    private Booking booking(Long id, Long customer, String country, String currency, double paid) { Booking value = new Booking(); value.setId(id); value.setCustomerId(customer); value.setCustomerEmail("customer@example.com"); value.setCountry(country); value.setCurrency(currency); value.setPaidAmount(paid); value.setPaymentTransactionId("cs_test"); return value; }
}
