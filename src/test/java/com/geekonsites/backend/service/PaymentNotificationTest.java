package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Notification;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.NotificationRepository;
import com.geekonsites.backend.repository.RefundRequestRepository;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentNotificationTest {

    @Test
    void remoteFullPaymentCreatesCorrectCustomerNotification() {
        Fixture fixture = fixture(remoteBooking());
        fixture.service.applyCompletedCheckoutSession(session("FULL", "paid", 10000L), 7L);
        verify(fixture.notifications).createPaymentSuccessNotification(
                argThat(booking -> booking.getCustomerId().equals(7L) && "PAID".equals(booking.getPaymentStatus())),
                eq("FULL"), eq("cs_paid"));
    }

    @Test
    void onsiteAdvanceUsesAdvancePaymentWording() {
        Booking booking = onsiteBooking("PENDING");
        Fixture fixture = fixture(booking);
        fixture.service.applyCompletedCheckoutSession(session("ADVANCE", "paid", 3000L), 7L);
        verify(fixture.notifications).createPaymentSuccessNotification(any(Booking.class), eq("ADVANCE"), eq("cs_paid"));
        assertEquals("PARTIALLY_PAID", booking.getPaymentStatus());
    }

    @Test
    void onsiteRemainingUsesRemainingPaymentType() {
        Booking booking = onsiteBooking("BALANCE_PENDING");
        booking.setAdvanceAmount(30.0);
        booking.setRemainingAmount(70.0);
        booking.setPaidAmount(30.0);
        Fixture fixture = fixture(booking);
        fixture.service.applyCompletedCheckoutSession(session("REMAINING", "paid", 7000L), 7L);
        verify(fixture.notifications).createPaymentSuccessNotification(any(Booking.class), eq("REMAINING"), eq("cs_paid"));
    }

    @Test
    void failedOrUnpaidStripeSessionCreatesNoSuccessNotification() {
        Fixture fixture = fixture(remoteBooking());
        assertThrows(RuntimeException.class,
                () -> fixture.service.applyCompletedCheckoutSession(session("FULL", "unpaid", 10000L), 7L));
        verifyNoInteractions(fixture.notifications);
        verify(fixture.bookings, never()).save(any());
    }

    @Test
    void webhookAndConfirmationRetriesCreateOnlyOneDatabaseNotification() {
        NotificationRepository repository = mock(NotificationRepository.class);
        PushNotificationService push = mock(PushNotificationService.class);
        NotificationService service = new NotificationService(repository, push);
        when(repository.existsByIdempotencyKey("PAYMENT_SUCCESS:1:cs_paid")).thenReturn(false, true);
        Booking booking = remoteBooking();

        service.createPaymentSuccessNotification(booking, "FULL", "cs_paid");
        service.createPaymentSuccessNotification(booking, "FULL", "cs_paid");

        verify(repository, times(1)).save(any(Notification.class));
    }

    @Test
    void databaseNotificationSurvivesUnavailablePushAndContainsAuditFields() {
        NotificationRepository repository = mock(NotificationRepository.class);
        PushNotificationService push = mock(PushNotificationService.class);
        doThrow(new RuntimeException("Firebase unavailable")).when(push)
                .sendToCustomer(anyLong(), anyString(), anyString(), anyString(), anyString());
        NotificationService service = new NotificationService(repository, push);
        Booking booking = remoteBooking();

        assertDoesNotThrow(() -> service.createPaymentSuccessNotification(booking, "FULL", "cs_paid"));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(captor.capture());
        Notification saved = captor.getValue();
        assertEquals(7L, saved.getCustomerId());
        assertEquals(1L, saved.getBookingId());
        assertEquals("CUSTOMER", saved.getRecipientRole());
        assertEquals("PAYMENT_SUCCESS", saved.getType());
        assertEquals("Payment Successful", saved.getTitle());
        assertEquals("/customer-dashboard?view=bookings", saved.getActionUrl());
    }

    private Fixture fixture(Booking booking) {
        BookingRepository bookings = mock(BookingRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        InvoiceService invoices = mock(InvoiceService.class);
        RemoteSessionProvisioningService remote = mock(RemoteSessionProvisioningService.class);
        when(bookings.findById(1L)).thenReturn(Optional.of(booking));
        when(bookings.save(any(Booking.class))).thenAnswer(call -> call.getArgument(0));
        when(remote.provisionAfterPayment(1L)).thenReturn(booking);
        PaymentService service = new PaymentService(bookings, invoices, remote,
                mock(UkEarlyServiceConsentService.class), mock(RefundRequestRepository.class), notifications);
        return new Fixture(service, bookings, notifications);
    }

    private Session session(String paymentType, String paymentStatus, Long amount) {
        Session session = mock(Session.class);
        when(session.getId()).thenReturn("cs_paid");
        when(session.getPaymentStatus()).thenReturn(paymentStatus);
        when(session.getMetadata()).thenReturn(Map.of("bookingId", "1", "paymentType", paymentType));
        when(session.getAmountTotal()).thenReturn(amount);
        when(session.getCurrency()).thenReturn("usd");
        return session;
    }

    private Booking remoteBooking() {
        Booking booking = base();
        booking.setServiceMode(ServiceMode.REMOTE);
        booking.setRemoteSessionRequired(true);
        return booking;
    }

    private Booking onsiteBooking(String paymentStatus) {
        Booking booking = base();
        booking.setServiceMode(ServiceMode.ONSITE);
        booking.setPaymentStatus(paymentStatus);
        booking.setAdvanceAmount(30.0);
        return booking;
    }

    private Booking base() {
        Booking booking = new Booking();
        booking.setId(1L);
        booking.setCustomerId(7L);
        booking.setCurrency("USD");
        booking.setTotalAmount(100.0);
        booking.setRemainingAmount(70.0);
        booking.setPaymentStatus("PENDING");
        return booking;
    }

    private record Fixture(PaymentService service, BookingRepository bookings,
                           NotificationService notifications) {}
}
