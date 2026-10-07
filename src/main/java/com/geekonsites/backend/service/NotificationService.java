package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.PageResponse;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Notification;
import com.geekonsites.backend.enums.NotificationType;
import com.geekonsites.backend.repository.NotificationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * PHASE 9 — single notification authority.
 *
 * <p>The durable in-app record is created first and is authoritative; push delivery is a
 * best-effort, non-transactional side effect that must never destroy or roll back the
 * record. The business {@link NotificationType} is always supplied explicitly — never
 * inferred from the display title.
 *
 * <p>Idempotency: important lifecycle notifications pass a deterministic dedupe key
 * ({@code type:subject:recipient}) which is persisted in the unique
 * {@code notifications.idempotency_key} column, so webhook/listener/HTTP retries create
 * exactly one in-app record.
 */
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final PushNotificationService pushNotificationService;

    public NotificationService(
            NotificationRepository notificationRepository,
            PushNotificationService pushNotificationService) {
        this.notificationRepository = notificationRepository;
        this.pushNotificationService = pushNotificationService;
    }

    // ============================================================ CUSTOMER

    public void createNotification(Long customerId, NotificationType type, String title, String message) {
        createNotification(customerId, type, title, message, null);
    }

    public void createNotification(Long customerId, NotificationType type, String title, String message, String dedupeKey) {
        if (customerId == null) return;
        Notification notification = build();
        notification.setCustomerId(customerId);
        notification.setRecipientRole("CUSTOMER");
        notification.setType(type.name());
        notification.setActionUrl("/customer-dashboard?view=bookings");
        persist(notification, title, message, dedupeKey,
                saved -> deliverToCustomer(customerId, title, message, saved));
    }

    /** @deprecated use the explicit {@link NotificationType} overload. */
    @Deprecated
    public void createNotification(Long customerId, String title, String message) {
        createNotification(customerId, NotificationType.BOOKING_UPDATE, title, message, null);
    }

    public PageResponse<Notification> listCustomerNotifications(Long customerId, Boolean read, Pageable pageable) {
        Page<Notification> page = read == null
                ? notificationRepository.findByCustomerId(customerId, pageable)
                : notificationRepository.findByCustomerIdAndIsRead(customerId, read, pageable);
        return PageResponse.of(page);
    }

    public Notification markCustomerNotificationRead(Long customerId, Long notificationId) {
        Notification notification = notificationRepository.findByIdAndCustomerId(notificationId, customerId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found"));
        notification.setIsRead(true);
        return notificationRepository.save(notification);
    }

    @Transactional
    public int markAllCustomerNotificationsRead(Long customerId) {
        return notificationRepository.markAllCustomerRead(customerId);
    }

    // ============================================================ TECHNICIAN

    public void createTechnicianNotification(Long technicianId, NotificationType type, String title, String message) {
        createTechnicianNotification(technicianId, type, title, message, null);
    }

    public void createTechnicianNotification(Long technicianId, NotificationType type, String title, String message, String dedupeKey) {
        if (technicianId == null) return;
        Notification notification = build();
        notification.setTechnicianId(technicianId);
        notification.setRecipientRole("TECHNICIAN");
        notification.setType(type.name());
        notification.setActionUrl("/technician-dashboard?view=notifications");
        persist(notification, title, message, dedupeKey,
                saved -> deliverToTechnician(technicianId, title, message, saved));
    }

    /** @deprecated use the explicit {@link NotificationType} overload. */
    @Deprecated
    public void createTechnicianNotification(Long technicianId, String title, String message) {
        createTechnicianNotification(technicianId, NotificationType.BOOKING_UPDATE, title, message, null);
    }

    public PageResponse<Notification> listTechnicianNotifications(Long technicianId, Boolean read, Pageable pageable) {
        Page<Notification> page = read == null
                ? notificationRepository.findByTechnicianId(technicianId, pageable)
                : notificationRepository.findByTechnicianIdAndIsRead(technicianId, read, pageable);
        return PageResponse.of(page);
    }

    public Notification markTechnicianNotificationRead(Long technicianId, Long notificationId) {
        Notification notification = notificationRepository.findByIdAndTechnicianId(notificationId, technicianId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found"));
        notification.setIsRead(true);
        return notificationRepository.save(notification);
    }

    @Transactional
    public int markAllTechnicianNotificationsRead(Long technicianId) {
        return notificationRepository.markAllTechnicianRead(technicianId);
    }

    // ============================================================ AGENT

    public void createAgentNotification(Long agentId, NotificationType type, String title, String message) {
        createAgentNotification(agentId, type, title, message, null);
    }

    public void createAgentNotification(Long agentId, NotificationType type, String title, String message, String dedupeKey) {
        if (agentId == null) return;
        Notification notification = build();
        notification.setAgentId(agentId);
        notification.setRecipientRole("AGENT");
        notification.setType(type.name());
        notification.setActionUrl("/agent-dashboard?view=notifications");
        persist(notification, title, message, dedupeKey,
                saved -> deliverToAgent(agentId, title, message, saved));
    }

    /** @deprecated use the explicit {@link NotificationType} overload. */
    @Deprecated
    public void createAgentNotification(Long agentId, String title, String message) {
        createAgentNotification(agentId, NotificationType.BOOKING_UPDATE, title, message, null);
    }

    public PageResponse<Notification> listAgentNotifications(Long agentId, Boolean read, Pageable pageable) {
        Page<Notification> page = read == null
                ? notificationRepository.findByAgentId(agentId, pageable)
                : notificationRepository.findByAgentIdAndIsRead(agentId, read, pageable);
        return PageResponse.of(page);
    }

    public Notification markAgentNotificationRead(Long agentId, Long notificationId) {
        Notification notification = notificationRepository.findByIdAndAgentId(notificationId, agentId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found"));
        notification.setIsRead(true);
        return notificationRepository.save(notification);
    }

    @Transactional
    public int markAllAgentNotificationsRead(Long agentId) {
        return notificationRepository.markAllAgentRead(agentId);
    }

    // ============================================================ ADMIN

    public void createAdminNotification(Long adminId, NotificationType type, String title, String message) {
        if (adminId == null) return;
        Notification notification = build();
        notification.setAdminId(adminId);
        notification.setRecipientRole("ADMIN");
        notification.setType(type.name());
        notification.setActionUrl("/admin-dashboard?view=notifications");
        persist(notification, title, message, null, saved -> { /* admin push not enabled */ });
    }

    public PageResponse<Notification> listAdminNotifications(Long adminId, Pageable pageable) {
        return PageResponse.of(notificationRepository.findByAdminId(adminId, pageable));
    }

    // ============================================================ PAYMENT (typed, deduped)

    public void createPaymentSuccessNotification(Booking booking, String paymentType, String stripeSessionId) {
        if (booking == null || booking.getCustomerId() == null || stripeSessionId == null || stripeSessionId.isBlank()) return;
        String normalizedType = paymentType == null ? "FULL" : paymentType.trim().toUpperCase();
        String dedupeKey = "PAYMENT_SUCCESS:" + booking.getId() + ":" + stripeSessionId;

        String title;
        String message;
        if ("ADVANCE".equals(normalizedType)) {
            title = "Advance Payment Successful";
            message = "Your advance payment for booking GOS-" + booking.getId() + " has been confirmed successfully.";
        } else if ("REMAINING".equals(normalizedType)) {
            title = "Remaining Payment Successful";
            message = "Your remaining payment for booking GOS-" + booking.getId() + " has been confirmed successfully.";
        } else {
            title = "Payment Successful";
            message = "Your payment for booking GOS-" + booking.getId() + " has been confirmed successfully.";
        }

        Notification notification = build();
        notification.setCustomerId(booking.getCustomerId());
        notification.setBookingId(booking.getId());
        notification.setRecipientRole("CUSTOMER");
        notification.setType(NotificationType.PAYMENT_SUCCESS.name());
        notification.setActionUrl("/customer-dashboard?view=bookings");
        persist(notification, title, message, dedupeKey,
                saved -> deliverToCustomer(booking.getCustomerId(), title, message, saved));
    }

    // ============================================================ legacy bounded getters

    /** Hard cap for the few role-specific compatibility endpoints that still return a list. */
    private static final int LEGACY_LIST_CAP = 100;

    @Deprecated
    public List<Notification> getCustomerNotifications(Long customerId) {
        return notificationRepository.findByCustomerId(customerId,
                PageRequest.of(0, LEGACY_LIST_CAP, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
    }

    @Deprecated
    public List<Notification> getTechnicianNotifications(Long technicianId) {
        return notificationRepository.findByTechnicianId(technicianId,
                PageRequest.of(0, LEGACY_LIST_CAP, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
    }

    @Deprecated
    public List<Notification> getAgentNotifications(Long agentId) {
        return notificationRepository.findByAgentId(agentId,
                PageRequest.of(0, LEGACY_LIST_CAP, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
    }

    @Deprecated
    public List<Notification> getAdminNotifications(Long adminId) {
        return notificationRepository.findByAdminId(adminId,
                PageRequest.of(0, LEGACY_LIST_CAP, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
    }

    // ============================================================ internals

    private Notification build() {
        Notification notification = new Notification();
        notification.setIsRead(false);
        notification.setCreatedAt(LocalDateTime.now());
        return notification;
    }

    private void persist(Notification notification, String title, String message, String dedupeKey,
                         Consumer<Notification> push) {
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setIdempotencyKey(dedupeKey);
        // Fast idempotency check; the unique DB index is the concurrency-safe backstop.
        if (dedupeKey != null && notificationRepository.existsByIdempotencyKey(dedupeKey)) {
            return;
        }
        try {
            notificationRepository.save(notification);
        } catch (DataIntegrityViolationException duplicate) {
            // A retry (webhook replay, listener redelivery, repeated HTTP action) already
            // created this exact notification; the in-app record is already authoritative.
            return;
        }
        // Push is best-effort and must never fail the caller or remove the record.
        try {
            push.accept(notification);
        } catch (RuntimeException ignored) {
            // provider failure is logged inside PushNotificationService.
        }
    }

    private void deliverToCustomer(Long customerId, String title, String message, Notification saved) {
        pushNotificationService.sendToCustomer(customerId, title, message, saved.getType(), saved.getActionUrl());
    }

    private void deliverToTechnician(Long technicianId, String title, String message, Notification saved) {
        pushNotificationService.sendToTechnician(technicianId, title, message, saved.getType(), saved.getActionUrl());
    }

    private void deliverToAgent(Long agentId, String title, String message, Notification saved) {
        pushNotificationService.sendToAgent(agentId, title, message, saved.getType(), saved.getActionUrl());
    }
}
