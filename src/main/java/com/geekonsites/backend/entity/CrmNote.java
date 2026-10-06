package com.geekonsites.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "crm_notes", indexes = @Index(name = "idx_crm_notes_customer", columnList = "customerId"))
@Data
public class CrmNote {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private Long customerId;
    private Long agentId;
    @Column(nullable = false) private String authorName;
    @Column(nullable = false) private String authorRole;
    @Column(nullable = false, columnDefinition = "TEXT") private String noteText;
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    @PrePersist void created() { createdAt = LocalDateTime.now(); }
}
