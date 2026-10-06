package com.geekonsites.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "remote_chat_messages", indexes = {
        @Index(name = "idx_remote_chat_booking_created", columnList = "booking_id,created_at")
})
@Data
public class RemoteChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;
    @Column(name = "sender_user_id", nullable = false)
    private Long senderUserId;
    @Column(name = "sender_role", nullable = false, length = 20)
    private String senderRole;
    @Column(nullable = false, length = 2000)
    private String message;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "read_at")
    private LocalDateTime readAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
