package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Notification;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.repository.NotificationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

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

    // CUSTOMER
    public void createNotification(Long customerId, String title, String message) {
        if (customerId == null) return;

        Notification notification = new Notification();
        notification.setCustomerId(customerId);
        notification.setRecipientRole("CUSTOMER");
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setType(notificationType(title));
        notification.setActionUrl("/customer-dashboard?view=bookings");
        notification.setIsRead(false);
        notification.setCreatedAt(LocalDateTime.now());

        notificationRepository.save(notification);
        pushNotificationService.sendToCustomer(
                customerId,
                title,
                message,
                notification.getType(),
                notification.getActionUrl());
    }

    public List<Notification> getCustomerNotifications(Long customerId) {
        return notificationRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    public void createPaymentSuccessNotification(Booking booking, String paymentType, String stripeSessionId) {
        if (booking == null || booking.getCustomerId() == null || stripeSessionId == null || stripeSessionId.isBlank()) return;
        String normalizedType = paymentType == null ? "FULL" : paymentType.trim().toUpperCase();
        String idempotencyKey = "PAYMENT_SUCCESS:" + booking.getId() + ":" + stripeSessionId;
        if (notificationRepository.existsByIdempotencyKey(idempotencyKey)) return;

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

        Notification notification = new Notification();
        notification.setCustomerId(booking.getCustomerId());
        notification.setBookingId(booking.getId());
        notification.setRecipientRole("CUSTOMER");
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setType("PAYMENT_SUCCESS");
        notification.setActionUrl("/customer-dashboard?view=bookings");
        notification.setIdempotencyKey(idempotencyKey);
        notification.setIsRead(false);
        notification.setCreatedAt(LocalDateTime.now());

        try {
            notificationRepository.save(notification);
        } catch (DataIntegrityViolationException duplicate) {
            return;
        }

        try {
            pushNotificationService.sendToCustomer(
                    booking.getCustomerId(), title, message,
                    notification.getType(), notification.getActionUrl());
        } catch (RuntimeException ignored) {
            // In-app notification is authoritative; optional push must never break payment confirmation.
        }
    }

    public Notification markCustomerNotificationRead(Long customerId, Long notificationId) {
        Notification notification = notificationRepository.findByIdAndCustomerId(notificationId, customerId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found"));
        notification.setIsRead(true);
        return notificationRepository.save(notification);
    }

    public List<Notification> markAllCustomerNotificationsRead(Long customerId) {
        List<Notification> notifications = getCustomerNotifications(customerId);
        notifications.forEach(notification -> notification.setIsRead(true));
        return notificationRepository.saveAll(notifications);
    }

    // TECHNICIAN
    public void createTechnicianNotification(Long technicianId, String title, String message) {
        if (technicianId == null) return;

        Notification notification = new Notification();
        notification.setTechnicianId(technicianId);
        notification.setRecipientRole("TECHNICIAN");
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setType(notificationType(title));
        notification.setActionUrl("/technician-dashboard?view=notifications");
        notification.setIsRead(false);
        notification.setCreatedAt(LocalDateTime.now());

        notificationRepository.save(notification);
        pushNotificationService.sendToTechnician(
                technicianId,
                title,
                message,
                notification.getType(),
                notification.getActionUrl());
    }

    public List<Notification> getTechnicianNotifications(Long technicianId) {
        return notificationRepository.findByTechnicianIdOrderByCreatedAtDesc(technicianId);
    }

    public Notification markTechnicianNotificationRead(Long technicianId, Long notificationId) {
        Notification notification = notificationRepository.findByIdAndTechnicianId(notificationId, technicianId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found"));
        notification.setIsRead(true);
        return notificationRepository.save(notification);
    }

    public List<Notification> markAllTechnicianNotificationsRead(Long technicianId) {
        List<Notification> notifications = getTechnicianNotifications(technicianId);
        notifications.forEach(notification -> notification.setIsRead(true));
        return notificationRepository.saveAll(notifications);
    }

    // AGENT
    public void createAgentNotification(Long agentId, String title, String message) {
        if (agentId == null) return;

        Notification notification = new Notification();
        notification.setAgentId(agentId);
        notification.setRecipientRole("AGENT");
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setType(notificationType(title));
        notification.setActionUrl("/agent-dashboard?view=notifications");
        notification.setIsRead(false);
        notification.setCreatedAt(LocalDateTime.now());

        notificationRepository.save(notification);
        pushNotificationService.sendToAgent(
                agentId,
                title,
                message,
                notification.getType(),
                notification.getActionUrl());
    }

    public List<Notification> getAgentNotifications(Long agentId) {
        return notificationRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
    }

    public Notification markAgentNotificationRead(Long agentId, Long notificationId) {
        Notification notification = notificationRepository.findByIdAndAgentId(notificationId, agentId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found"));
        notification.setIsRead(true);
        return notificationRepository.save(notification);
    }

    public List<Notification> markAllAgentNotificationsRead(Long agentId) {
        List<Notification> notifications = getAgentNotifications(agentId);
        notifications.forEach(notification -> notification.setIsRead(true));
        return notificationRepository.saveAll(notifications);
    }

    // ADMIN
    public void createAdminNotification(Long adminId, String title, String message) {
        if (adminId == null) return;

        Notification notification = new Notification();
        notification.setAdminId(adminId);
        notification.setRecipientRole("ADMIN");
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setIsRead(false);
        notification.setCreatedAt(LocalDateTime.now());

        notificationRepository.save(notification);
    }

    public List<Notification> getAdminNotifications(Long adminId) {
        return notificationRepository.findByAdminIdOrderByCreatedAtDesc(adminId);
    }

    private String notificationType(String title) {
        String value = String.valueOf(title).toLowerCase();
        if (value.contains("created")) return "BOOKING_CREATED";
        if (value.contains("assigned")) return "TECHNICIAN_ASSIGNED";
        if (value.contains("accepted")) return "TECHNICIAN_ACCEPTED";
        if (value.contains("on the way")) return "TECHNICIAN_ON_THE_WAY";
        if (value.contains("arrived")) return "TECHNICIAN_ARRIVED";
        if (value.contains("started")) return "SERVICE_STARTED";
        if (value.contains("remote") || value.contains("meeting")) return "REMOTE_SESSION";
        if (value.contains("payment")) return "PAYMENT";
        if (value.contains("invoice")) return "INVOICE";
        if (value.contains("completed")) return "SERVICE_COMPLETED";
        if (value.contains("closed")) return "BOOKING_CLOSED";
        return "BOOKING_UPDATE";
    }
}
