package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.AdminDashboardStats;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.repository.AgentRepository;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {
    @Mock UserRepository users;
    @Mock TechnicianRepository technicians;
    @Mock AgentRepository agents;
    @Mock BookingRepository bookings;
    @InjectMocks AdminService service;

    @Test
    void dashboardCountsUseTheSameAuthoritativePopulationsAsAdminLists() {
        when(users.countByRole(Role.CUSTOMER)).thenReturn(3L);
        when(technicians.count()).thenReturn(4L);
        when(agents.count()).thenReturn(2L);
        when(bookings.count()).thenReturn(5L);
        when(bookings.findAll()).thenReturn(List.of());

        AdminDashboardStats stats = service.getDashboardStats();

        assertEquals(3L, stats.getTotalCustomers());
        assertEquals(4L, stats.getTotalTechnicians());
        assertEquals(2L, stats.getTotalAgents());
        assertEquals(5L, stats.getTotalBookings());
        verify(users).countByRole(Role.CUSTOMER);
        verify(users, never()).count();
    }
}
