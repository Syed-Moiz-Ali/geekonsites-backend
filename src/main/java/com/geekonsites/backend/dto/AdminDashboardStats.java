package com.geekonsites.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AdminDashboardStats {

    private Long totalCustomers;
    private Long totalTechnicians;
    private Long totalAgents;
    private Long totalBookings;

    private Double totalRevenue;

    private Long activeJobs;
    private Long completedJobs;
    private Long pendingJobs;
}