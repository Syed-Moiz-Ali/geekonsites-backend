package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.AdminDashboardStats;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.repository.AgentRepository;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AdminService {

    private final UserRepository userRepository;
    private final TechnicianRepository technicianRepository;
    private final AgentRepository agentRepository;
    private final BookingRepository bookingRepository;

    public AdminService(
            UserRepository userRepository,
            TechnicianRepository technicianRepository,
            AgentRepository agentRepository,
            BookingRepository bookingRepository
    ) {
        this.userRepository = userRepository;
        this.technicianRepository = technicianRepository;
        this.agentRepository = agentRepository;
        this.bookingRepository = bookingRepository;
    }

    public AdminDashboardStats getDashboardStats() {

        // Keep the overview count on the same authoritative population used
        // by Admin > Customers. users.count() also includes admins, agents,
        // and technician login rows and therefore inflated this metric.
        long totalCustomers = userRepository.countByRole(Role.CUSTOMER);
        long totalTechnicians = technicianRepository.count();
        long totalAgents = agentRepository.count();
        long totalBookings = bookingRepository.count();

        List<Booking> bookings = bookingRepository.findAll();

        double totalRevenue = bookings.stream()
                .filter(b -> b.getPaidAmount() != null)
                .mapToDouble(Booking::getPaidAmount)
                .sum();

        long activeJobs = bookings.stream()
                .filter(b ->
                        b.getBookingStatus() == BookingStatus.TECHNICIAN_ASSIGNED ||
                        b.getBookingStatus() == BookingStatus.TECHNICIAN_ON_THE_WAY ||
                        b.getBookingStatus() == BookingStatus.SERVICE_STARTED ||
                        b.getBookingStatus() == BookingStatus.REMOTE_SESSION_STARTED
                )
                .count();

        long completedJobs = bookings.stream()
                .filter(b -> b.getBookingStatus() == BookingStatus.SERVICE_COMPLETED)
                .count();

        long pendingJobs = bookings.stream()
                .filter(b ->
                        b.getBookingStatus() == BookingStatus.PENDING ||
                        b.getBookingStatus() == BookingStatus.ASSIGNMENT_PENDING
                )
                .count();

        return new AdminDashboardStats(
                totalCustomers,
                totalTechnicians,
                totalAgents,
                totalBookings,
                totalRevenue,
                activeJobs,
                completedJobs,
                pendingJobs
        );
    }

    public List<Booking> getRemoteSessions() {
        return bookingRepository.findAll().stream()
                .filter(booking -> Boolean.TRUE.equals(booking.getRemoteSessionRequired()) ||
                        (booking.getServiceMode() != null && "REMOTE".equals(booking.getServiceMode().name())))
                .sorted((left, right) -> Long.compare(right.getId(), left.getId()))
                .toList();
    }


}
