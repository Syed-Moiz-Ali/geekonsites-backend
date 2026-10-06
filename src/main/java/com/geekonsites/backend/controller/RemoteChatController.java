package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.RemoteChatMessageRequest;
import com.geekonsites.backend.entity.RemoteChatMessage;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.service.RemoteChatService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/remote-session-chat")
public class RemoteChatController {
    private final RemoteChatService chatService;

    public RemoteChatController(RemoteChatService chatService) { this.chatService = chatService; }

    @GetMapping("/{bookingId}/messages")
    public ResponseEntity<List<RemoteChatMessage>> history(@PathVariable Long bookingId, Authentication authentication) {
        return ResponseEntity.ok(chatService.history(bookingId, principal(authentication)));
    }

    @PostMapping("/{bookingId}/messages")
    public ResponseEntity<RemoteChatMessage> send(@PathVariable Long bookingId,
                                                   @Valid @RequestBody RemoteChatMessageRequest request,
                                                   Authentication authentication) {
        return ResponseEntity.ok(chatService.send(bookingId, request.getMessage(), principal(authentication)));
    }

    private User principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) return null;
        return user;
    }
}
