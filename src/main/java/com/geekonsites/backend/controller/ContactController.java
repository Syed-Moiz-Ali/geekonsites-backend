package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.ContactRequest;
import com.geekonsites.backend.entity.ContactMessage;
import com.geekonsites.backend.service.ContactService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.security.core.Authentication;
import com.geekonsites.backend.entity.User;

@RestController
@RequestMapping("/api/contact")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService contactService;

    @PostMapping
    public ResponseEntity<ContactMessage> submitMessage(
            @Valid @RequestBody ContactRequest request,
            Authentication authentication) {

        User customer = authentication != null && authentication.getPrincipal() instanceof User user ? user : null;
        return ResponseEntity.ok(contactService.saveMessage(request, customer));
    }

    @GetMapping
    public ResponseEntity<List<ContactMessage>> getAllMessages() {
        return ResponseEntity.ok(contactService.getAllMessages());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ContactMessage> getMessage(@PathVariable Long id) {
        return ResponseEntity.ok(contactService.getMessageById(id));
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<ContactMessage> markAsRead(@PathVariable Long id) {
        return ResponseEntity.ok(contactService.markAsRead(id));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<ContactMessage> updateStatus(
            @PathVariable Long id,
            @RequestParam String status) {
        return ResponseEntity.ok(contactService.updateStatus(id, status));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteMessage(@PathVariable Long id) {

        contactService.deleteMessage(id);

        return ResponseEntity.ok("Message deleted successfully");
    }
}
