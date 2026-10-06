package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.PushDeviceToken;
import com.geekonsites.backend.repository.PushDeviceTokenRepository;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves PushNotificationService can never break app/bean startup or the
 * booking/customer/notification flows that depend on it, regardless of
 * whether Firebase credentials are present, invalid, or absent (as on
 * Render, which has no Google Application Default Credentials).
 */
class PushNotificationServiceTest {

    private PushNotificationService newService(PushDeviceTokenRepository repository) {
        return new PushNotificationService(repository);
    }

    @Test
    void initializeFirebaseDoesNotThrowWhenCredentialsAreMissingOrInvalid() {
        PushDeviceTokenRepository repository = mock(PushDeviceTokenRepository.class);
        PushNotificationService service = newService(repository);
        ReflectionTestUtils.setField(service, "firebaseEnabled", true);
        // Deliberately not valid service-account JSON, and no real ADC is
        // available in this environment either — mirrors production on
        // Render, where credentials are absent.
        ReflectionTestUtils.setField(service, "serviceAccountJson", "not-a-valid-service-account-json");

        try (MockedStatic<FirebaseApp> firebaseApp = mockStatic(FirebaseApp.class)) {
            firebaseApp.when(FirebaseApp::getApps).thenReturn(List.of());

            // This is the exact bean-initialization path Spring runs via
            // @PostConstruct. It must never throw, or bean creation (and
            // every controller that transitively depends on this service)
            // fails with a 500, as it did in production.
            assertDoesNotThrow(service::initializeFirebase);
        }

        assertDoesNotThrow(() -> service.sendToCustomer(1L, "Title", "Body", "BOOKING_UPDATE", "/notifications"));
    }

    @Test
    void sendMethodsNoOpWithoutTouchingRepositoryWhenFirebaseUnavailable() {
        PushDeviceTokenRepository repository = mock(PushDeviceTokenRepository.class);
        PushNotificationService service = newService(repository);
        ReflectionTestUtils.setField(service, "firebaseEnabled", true);
        ReflectionTestUtils.setField(service, "serviceAccountJson", "not-a-valid-service-account-json");

        try (MockedStatic<FirebaseApp> firebaseApp = mockStatic(FirebaseApp.class)) {
            firebaseApp.when(FirebaseApp::getApps).thenReturn(List.of());
            service.initializeFirebase();
        }

        service.sendToCustomer(1L, "Title", "Body", "BOOKING_UPDATE", "/notifications");
        service.sendToTechnician(2L, "Title", "Body", "BOOKING_UPDATE", "/notifications");
        service.sendToAgent(3L, "Title", "Body", "BOOKING_UPDATE", "/notifications");

        verify(repository, never()).findByCustomerIdAndRecipientRoleAndActiveTrue(anyLong(), anyString());
    }

    @Test
    void sendMethodsNoOpWhenFirebaseIsDisabledByConfig() {
        PushDeviceTokenRepository repository = mock(PushDeviceTokenRepository.class);
        PushNotificationService service = newService(repository);
        // firebase.enabled defaults to false.

        assertDoesNotThrow(service::initializeFirebase);
        assertDoesNotThrow(() -> service.sendToCustomer(1L, "Title", "Body", "BOOKING_UPDATE", "/notifications"));

        verify(repository, never()).findByCustomerIdAndRecipientRoleAndActiveTrue(anyLong(), anyString());
    }

    @Test
    void registerAndUnregisterStillWorkWhenFirebaseIsUnavailable() {
        PushDeviceTokenRepository repository = mock(PushDeviceTokenRepository.class);
        PushNotificationService service = newService(repository);
        // firebase.enabled defaults to false, so Firebase never initializes.

        PushDeviceToken device = new PushDeviceToken();
        when(repository.findByToken("device-token")).thenReturn(java.util.Optional.of(device));
        when(repository.save(any(PushDeviceToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PushDeviceToken registered = service.register(1L, "CUSTOMER", "device-token", "ANDROID");
        assertDoesNotThrow(() -> service.unregister(1L, "CUSTOMER", "device-token"));

        verify(repository, times(2)).save(any(PushDeviceToken.class));
        org.junit.jupiter.api.Assertions.assertEquals(1L, registered.getCustomerId());
    }

    @Test
    void normalFirebaseEnabledBehaviorIsPreservedWhenFirebaseIsAvailable() throws FirebaseMessagingException {
        PushDeviceTokenRepository repository = mock(PushDeviceTokenRepository.class);
        PushNotificationService service = newService(repository);
        ReflectionTestUtils.setField(service, "firebaseEnabled", true);

        PushDeviceToken device = new PushDeviceToken();
        device.setToken("device-token");
        device.setActive(true);
        when(repository.findByCustomerIdAndRecipientRoleAndActiveTrue(1L, "CUSTOMER")).thenReturn(List.of(device));

        try (MockedStatic<FirebaseApp> firebaseApp = mockStatic(FirebaseApp.class);
             MockedStatic<FirebaseMessaging> firebaseMessaging = mockStatic(FirebaseMessaging.class)) {
            // Simulate an already-initialized Firebase app (the common case
            // once valid credentials load successfully at startup).
            firebaseApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            service.initializeFirebase();

            FirebaseMessaging messaging = mock(FirebaseMessaging.class);
            firebaseMessaging.when(FirebaseMessaging::getInstance).thenReturn(messaging);
            when(messaging.send(any(Message.class))).thenReturn("projects/x/messages/1");

            service.sendToCustomer(1L, "Title", "Body", "BOOKING_UPDATE", "/notifications");

            verify(messaging, times(1)).send(any(Message.class));
        }
    }
}
