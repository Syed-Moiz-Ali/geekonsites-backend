package com.geekonsites.backend.dto;

import java.util.Map;

public final class AgentOperationsDtos {
    private AgentOperationsDtos() {}

    public record BookingCounts(long total, long pending, long assigned, long completed, long remoteSessions) {}
    public record PeriodCounts(long created, long pending, long assigned, long completed,
                               long onsite, long remote, long supportRequests) {}
    public record TopMetrics(long totalAgents, long agentsWithActiveJobs, long activeJobs, long needsAttention) {}
    public record SummaryRow(Long active, Long pending, long total) {}
    public record TechnicianCounts(long approved, long available, long busy, long unavailable, long total) {}
    public record ModeCounts(long pending, long assigned, long active, long completed, long total) {}
    public record CrmCounts(long customers, long openEnquiries, long followUpsDue, long completedFollowUps) {}
    public record SupportCounts(long open, long inProgress, long closed, long total) {}
    public record AgentWorkload(Long agentId, String name, String email, String country, String region,
                                long assignedBookings, long actionNeeded, long activeWorkload) {}
    public record DashboardSummary(BookingCounts bookings, long customers, long technicians,
                                   long availableTechnicians, PeriodCounts today, AgentWorkload workload,
                                   TopMetrics topMetrics, Map<String, SummaryRow> systemSummary,
                                   PeriodCounts yesterday, TechnicianCounts technicianOperations,
                                   Map<String, Long> bookingLifecycle, ModeCounts onsite,
                                   ModeCounts remote, CrmCounts crm, SupportCounts support,
                                   String timezone) {}
}
