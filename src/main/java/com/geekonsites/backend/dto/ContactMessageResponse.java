package com.geekonsites.backend.dto;

import com.geekonsites.backend.entity.ContactMessage;

import java.time.LocalDateTime;

/** PHASE 9 — stable contact-message projection for the paginated operational feed. */
public record ContactMessageResponse(
        Long id,
        Long customerId,
        String fullName,
        String email,
        String phone,
        String country,
        String subject,
        String message,
        String status,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt
) {
    public static ContactMessageResponse from(ContactMessage message) {
        return new ContactMessageResponse(
                message.getId(), message.getCustomerId(), message.getFullName(), message.getEmail(),
                message.getPhone(), message.getCountry(), message.getSubject(), message.getMessage(),
                message.getStatus(), message.getCreatedAt(), message.getResolvedAt());
    }
}
