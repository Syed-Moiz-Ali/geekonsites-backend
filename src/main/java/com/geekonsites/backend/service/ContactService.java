package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.ContactRequest;
import com.geekonsites.backend.entity.ContactMessage;
import com.geekonsites.backend.repository.ContactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.time.LocalDateTime;
import com.geekonsites.backend.entity.User;

@Service
@RequiredArgsConstructor
public class ContactService {

    private final ContactRepository contactRepository;
    private final EmailService emailService;

    public ContactMessage saveMessage(ContactRequest request, User customer) {

        ContactMessage message = ContactMessage.builder()
                .customerId(customer == null ? null : customer.getId())
                .fullName(request.getFullName())
                .email(request.getEmail())
                .phone(request.getPhone() == null ? "" : request.getPhone())
                .country(request.getCountry())
                .subject(request.getSubject())
                .message(request.getMessage())
                .status("NEW")
                .build();

        ContactMessage savedMessage = contactRepository.save(message);
        emailService.sendContactMessageEmails(savedMessage);
        return savedMessage;
    }

    public List<ContactMessage> getAllMessages() {
        return contactRepository.findAllByOrderByCreatedAtDesc();
    }

    /** PHASE 9 — bounded, DB-side paginated operational feed. */
    public com.geekonsites.backend.dto.PageResponse<com.geekonsites.backend.dto.ContactMessageResponse> getAllMessages(
            org.springframework.data.domain.Pageable pageable) {
        return com.geekonsites.backend.dto.PageResponse.of(
                contactRepository.findAllByOrderByCreatedAtDesc(pageable),
                com.geekonsites.backend.dto.ContactMessageResponse::from);
    }

    public ContactMessage getMessageById(Long id) {
        // PHASE 9 E2E fix: missing contact message is a 404, not a 500.
        return contactRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
    }

    public ContactMessage markAsRead(Long id) {

        ContactMessage message = getMessageById(id);

        message.setStatus("READ");

        return contactRepository.save(message);
    }

    public ContactMessage updateStatus(Long id, String requestedStatus) {
        String status = requestedStatus == null ? "" : requestedStatus.trim().toUpperCase();
        if (!List.of("NEW", "READ", "RESOLVED").contains(status)) {
            throw new IllegalArgumentException("Status must be NEW, READ, or RESOLVED");
        }

        ContactMessage message = getMessageById(id);
        message.setStatus(status);
        message.setResolvedAt("RESOLVED".equals(status) ? LocalDateTime.now() : null);
        return contactRepository.save(message);
    }

    public void deleteMessage(Long id) {
        contactRepository.deleteById(id);
    }
}
