package com.geekonsites.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AgentDashboardStats {

    private Long totalBookings;
    private Long activeBookings;
    private Long completedBookings;
    private Long pendingBookings;
}