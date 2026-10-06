package com.geekonsites.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "crm_follow_ups", indexes = {
        @Index(name = "idx_crm_followup_customer", columnList = "customerId"),
        @Index(name = "idx_crm_followup_due", columnList = "status,followUpAt")
})
@Data
public class CrmFollowUp {
    public enum Status { PENDING, COMPLETED, CANCELLED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long customerId;
    private Long agentId;
    @Column(nullable = false) private String ownerName;
    @Column(nullable = false) private String ownerRole;
    @Column(nullable = false) private String reason;
    @Column(columnDefinition = "TEXT") private String internalNote;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.PENDING;
    @Column(nullable = false) private LocalDateTime followUpAt;
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    @PrePersist void created() { createdAt = LocalDateTime.now(); }
}
