package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.AgentRequest;
import com.geekonsites.backend.entity.Agent;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.repository.AgentRepository;
import com.geekonsites.backend.repository.BookingRepository;
import org.springframework.stereotype.Service;
import com.geekonsites.backend.dto.AgentDashboardStats;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

@Service
public class AgentService {

    private final AgentRepository agentRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notificationService;


    public AgentService(
            AgentRepository agentRepository,
            BookingRepository bookingRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            NotificationService notificationService
    ) {
        this.agentRepository = agentRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationService = notificationService;
    }

     public Agent createAgent(AgentRequest request) {

    if (userRepository.findByEmail(request.getEmail()).isPresent()) {
        throw new RuntimeException("Email already exists");
    }

    User user = new User();
    user.setFullName(request.getName());
    user.setEmail(request.getEmail());
    user.setPassword(
            passwordEncoder.encode(request.getPassword())
    );
    user.setPhone(request.getPhone());
    user.setRole(Role.AGENT);

    userRepository.save(user);

    Agent agent = new Agent();

    agent.setName(request.getName());
    agent.setEmail(request.getEmail());
    agent.setPhone(request.getPhone());
    agent.setCountry(request.getCountry());
    agent.setCity(request.getCity());
    agent.setStatus("ACTIVE");

    return agentRepository.save(agent);
}

    public List<Agent> getAllAgents() {
        return agentRepository.findAll();
    }

    public Agent getAgentById(Long id) {
        return agentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Agent not found"));
    }

    public List<Booking> getAgentBookings(Long agentId) {
        return bookingRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
    }
    
    public AgentDashboardStats getDashboardStats(Long agentId) {

    List<Booking> bookings =
            bookingRepository.findByAgentIdOrderByCreatedAtDesc(agentId);

    long totalBookings = bookings.size();

    long activeBookings = bookings.stream()
            .filter(b ->
                    b.getBookingStatus() == BookingStatus.TECHNICIAN_ASSIGNED ||
                    b.getBookingStatus() == BookingStatus.TECHNICIAN_ON_THE_WAY ||
                    b.getBookingStatus() == BookingStatus.SERVICE_STARTED ||
                    b.getBookingStatus() == BookingStatus.REMOTE_SESSION_STARTED
            )
            .count();

    long completedBookings = bookings.stream()
            .filter(b ->
                    b.getBookingStatus() == BookingStatus.SERVICE_COMPLETED
            )
            .count();

    long pendingBookings = bookings.stream()
            .filter(b ->
                    b.getBookingStatus() == BookingStatus.PENDING ||
                    b.getBookingStatus() == BookingStatus.ASSIGNMENT_PENDING
            )
            .count();

    return new AgentDashboardStats(
            totalBookings,
            activeBookings,
            completedBookings,
            pendingBookings
    );
}

public List<Booking> getUnassignedBookings() {
    return bookingRepository.findByTechnicianIdIsNullOrderByCreatedAtDesc();
}

public Booking assignBookingToAgent(
        Long bookingId,
        Long agentId
) {
    Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new RuntimeException("Booking not found"));

    Agent agent = agentRepository.findById(agentId)
            .orElseThrow(() -> new RuntimeException("Agent not found"));

    booking.setAgentId(agent.getId());
    booking.setAgentName(agent.getName());

    Booking saved = bookingRepository.save(booking);
    notificationService.createAgentNotification(
            agent.getId(),
            com.geekonsites.backend.enums.NotificationType.TECHNICIAN_ASSIGNED,
            "Booking assigned",
            "Booking GOS-" + saved.getId() + " is now assigned to you. Open the agent dashboard to review and coordinate it.",
            "AGENT_BOOKING_ASSIGNED:" + saved.getId() + ":" + agent.getId()
    );
    return saved;
}

}
