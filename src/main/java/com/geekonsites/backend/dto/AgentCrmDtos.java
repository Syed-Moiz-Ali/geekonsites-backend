package com.geekonsites.backend.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class AgentCrmDtos {
    private AgentCrmDtos() {}
    public record CustomerRow(Long id, String name, String email, String phone, String country,
        long bookingCount, LocalDateTime latestBookingDate, String latestBookingStatus,
        String serviceMode, String assignedTechnician, String paymentStatus,
        LocalDateTime lastInteractionDate, String followUpStatus) {}
    public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}
    public record BookingRow(Long id, String service, String serviceMode, String status,
        LocalDate date, String technician, String paymentStatus) {}
    public record EnquiryRow(Long id, Long customerId, String name, String email, String phone,
        String country, String subject, String message, String status, LocalDateTime createdAt) {}
    public record NoteRow(Long id, Long customerId, Long agentId, String authorName,
        String noteText, LocalDateTime createdAt) {}
    public record FollowUpRow(Long id, Long customerId, Long agentId, String ownerName,
        LocalDateTime followUpAt, String reason, String status, String internalNote,
        LocalDateTime createdAt, LocalDateTime completedAt, String timing) {}
    public record CustomerDetail(Long id, String name, String email, String phone, String country,
        String address, List<BookingRow> bookings, List<EnquiryRow> enquiries,
        List<NoteRow> notes, List<FollowUpRow> followUps) {}
    public record Summary(long totalActiveCustomers, long openEnquiries, long activeBookings,
        long followUpsDueToday, long overdueFollowUps) {}
    public record NoteRequest(@NotBlank @Size(max=4000) String noteText) {}
    public record FollowUpRequest(@NotNull @FutureOrPresent LocalDateTime followUpAt,
        @NotBlank @Size(max=250) String reason, @Size(max=2000) String internalNote) {}
}
