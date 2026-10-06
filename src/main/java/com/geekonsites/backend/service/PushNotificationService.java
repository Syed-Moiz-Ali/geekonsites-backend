package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.PushDeviceToken;
import com.geekonsites.backend.repository.PushDeviceTokenRepository;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PushNotificationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PushNotificationService.class);

    private final PushDeviceTokenRepository tokenRepository;

    @Value("${firebase.enabled:false}")
    private boolean firebaseEnabled;

    @Value("${firebase.service-account-json:}")
    private String serviceAccountJson;

    // Tracks whether Firebase actually finished initializing successfully.
    // Push notifications are best-effort: this bean (and anything that
    // depends on it, e.g. booking/customer notification flows) must always
    // construct successfully even when Firebase credentials are missing,
    // invalid, or unavailable (as on Render, which has no Google Application
    // Default Credentials). Send methods check this before touching
    // FirebaseMessaging so they no-op instead of throwing.
    private volatile boolean firebaseAvailable = false;

    @PostConstruct
    void initializeFirebase() {
        if (!firebaseEnabled) return;
        if (!FirebaseApp.getApps().isEmpty()) {
            firebaseAvailable = true;
            return;
        }
        try {
            FirebaseOptions.Builder options = FirebaseOptions.builder();
            if (!serviceAccountJson.isBlank()) {
                options.setCredentials(GoogleCredentials.fromStream(
                        new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8))));
            } else {
                options.setCredentials(GoogleCredentials.getApplicationDefault());
            }
            FirebaseApp.initializeApp(options.build());
            firebaseAvailable = true;
        } catch (Exception error) {
            firebaseAvailable = false;
            LOGGER.warn("Firebase push initialization failed; push notifications are disabled.", error);
        }
    }

    private boolean isPushAvailable() {
        return firebaseEnabled && firebaseAvailable && !FirebaseApp.getApps().isEmpty();
    }

    public PushDeviceToken register(Long recipientId, String recipientRole, String token, String platform) {
        PushDeviceToken device = tokenRepository.findByToken(token).orElseGet(PushDeviceToken::new);
        device.setCustomerId(recipientId);
        device.setRecipientRole(recipientRole);
        device.setToken(token);
        device.setPlatform(platform.toLowerCase());
        device.setActive(true);
        return tokenRepository.save(device);
    }

    public void unregister(Long recipientId, String recipientRole, String token) {
        tokenRepository.findByToken(token).filter(device -> recipientId.equals(device.getCustomerId()) && recipientRole.equals(device.getRecipientRole())).ifPresent(device -> {
            device.setActive(false);
            tokenRepository.save(device);
        });
    }

    public void sendToCustomer(Long customerId, String title, String body, String type, String actionUrl) {
        sendToRecipient(customerId, "CUSTOMER", title, body, type, actionUrl);
    }

    public void sendToTechnician(Long technicianId, String title, String body, String type, String actionUrl) {
        sendToRecipient(technicianId, "TECHNICIAN", title, body, type, actionUrl);
    }

    public void sendToAgent(Long agentId, String title, String body, String type, String actionUrl) {
        sendToRecipient(agentId, "AGENT", title, body, type, actionUrl);
    }

    private void sendToRecipient(Long recipientId, String role, String title, String body, String type, String actionUrl) {
        if (!isPushAvailable() || recipientId == null) return;
        List<PushDeviceToken> devices = tokenRepository.findByCustomerIdAndRecipientRoleAndActiveTrue(recipientId, role);
        for (PushDeviceToken device : devices) {
            try {
                Message message = Message.builder()
                        .setToken(device.getToken())
                        .setNotification(com.google.firebase.messaging.Notification.builder().setTitle(title).setBody(body).build())
                        .putData("type", type == null ? "BOOKING_UPDATE" : type)
                        .putData("actionUrl", actionUrl == null ? "/notifications" : actionUrl)
                        .build();
                FirebaseMessaging.getInstance().send(message);
            } catch (Exception error) {
                String message = String.valueOf(error.getMessage()).toLowerCase();
                if (message.contains("registration-token-not-registered") || message.contains("invalid registration")) {
                    device.setActive(false);
                    tokenRepository.save(device);
                }
            }
        }
    }
}
