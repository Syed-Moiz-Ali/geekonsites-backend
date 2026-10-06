package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.AgentOperationsDtos.*;
import com.geekonsites.backend.entity.Agent;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.entity.CrmFollowUp;
import com.geekonsites.backend.repository.AgentRepository;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.repository.UserRepository;
import com.geekonsites.backend.repository.ContactRepository;
import com.geekonsites.backend.repository.CrmFollowUpRepository;
import com.geekonsites.backend.repository.projection.AgentBookingQueueView;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentOperationsService {
    static final List<BookingStatus> PENDING = List.of(BookingStatus.PENDING, BookingStatus.ASSIGNMENT_PENDING);
    static final List<BookingStatus> ASSIGNED = List.of(BookingStatus.TECHNICIAN_ASSIGNED,
            BookingStatus.TECHNICIAN_ACCEPTED, BookingStatus.TECHNICIAN_ON_THE_WAY,
            BookingStatus.TECHNICIAN_ARRIVED, BookingStatus.SERVICE_STARTED,
            BookingStatus.REMOTE_SESSION_STARTED);
    static final List<BookingStatus> CLOSED = List.of(BookingStatus.SERVICE_COMPLETED,
            BookingStatus.BOOKING_CLOSED, BookingStatus.CANCELLED);

    private final BookingRepository bookings;
    private final TechnicianRepository technicians;
    private final UserRepository users;
    private final AgentRepository agents;
    private final ContactRepository contacts;
    private final CrmFollowUpRepository followUps;
    private final Clock clock;

    @Transactional(readOnly = true)
    public DashboardSummary summary(Authentication authentication) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        LocalDateTime yesterdayStart = today.minusDays(1).atStartOfDay();
        BookingCounts bookingCounts = bookings.countOperations(PENDING, ASSIGNED);
        PeriodCounts todayCounts = withSupport(bookings.countPeriod(start, end, PENDING, ASSIGNED), start, end);
        PeriodCounts yesterdayCounts = withSupport(bookings.countPeriod(yesterdayStart, start, PENDING, ASSIGNED), yesterdayStart, start);
        long available = technicians.countByVerificationStatusIgnoreCaseAndAvailabilityStatusIgnoreCase("APPROVED", "AVAILABLE");
        long approved = technicians.countByVerificationStatusIgnoreCase("APPROVED");
        long agentTotal = agents.count();
        long agentActive = bookings.countDistinctAgentsWithActiveBookings(CLOSED);
        Map<String, Long> lifecycle = lifecycle();
        long activeJobs = Math.max(0, bookingCounts.total() - countStatuses(lifecycle, CLOSED));
        long supportNew = contacts.countByStatusIgnoreCase("NEW");
        long supportInProgress = contacts.countByStatusIgnoreCase("IN_PROGRESS");
        long supportClosed = contacts.countByStatusIgnoreCase("RESOLVED");
        long supportTotal = contacts.count();
        TechnicianCounts technicianCounts = new TechnicianCounts(approved, available,
                technicians.countByVerificationStatusIgnoreCaseAndAvailabilityStatusIgnoreCaseNot("APPROVED", "AVAILABLE"),
                Math.max(0, approved - available), technicians.count());
        ModeCounts onsite = modeCounts(ServiceMode.ONSITE);
        ModeCounts remote = modeCounts(ServiceMode.REMOTE);
        Map<String, SummaryRow> system = new LinkedHashMap<>();
        system.put("agents", new SummaryRow(agentActive, null, agentTotal));
        system.put("customers", new SummaryRow(null, null, users.countByRole(Role.CUSTOMER)));
        system.put("bookings", new SummaryRow(activeJobs, bookingCounts.pending(), bookingCounts.total()));
        system.put("technicians", new SummaryRow(available, technicians.countByVerificationStatusIgnoreCase("PENDING"), technicians.count()));
        system.put("remoteSessions", new SummaryRow(remote.active(), remote.pending(), remote.total()));
        system.put("supportRequests", new SummaryRow(supportNew + supportInProgress, supportNew, supportTotal));
        AgentWorkload workload = workload(authentication);
        return new DashboardSummary(bookingCounts, users.countByRole(Role.CUSTOMER), technicians.count(),
                available, todayCounts, workload,
                new TopMetrics(agentTotal, agentActive, activeJobs, bookingCounts.pending()), system,
                yesterdayCounts, technicianCounts, lifecycle, onsite, remote,
                new CrmCounts(users.countByRole(Role.CUSTOMER), supportNew,
                        followUps.countByStatusAndFollowUpAtLessThanEqual(CrmFollowUp.Status.PENDING, end),
                        followUps.countByStatus(CrmFollowUp.Status.COMPLETED)),
                new SupportCounts(supportNew, supportInProgress, supportClosed, supportTotal),
                clock.getZone().getId());
    }

    @Transactional(readOnly = true)
    public Page<AgentBookingQueueView> bookingQueue(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        return bookings.findAllProjectedBy(PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    private AgentWorkload workload(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (admin) return null;
        Agent agent = agents.findByEmail(authentication.getName()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent profile not found"));
        long active = bookings.countActiveAssignedToAgent(agent.getId(), CLOSED);
        return new AgentWorkload(agent.getId(), agent.getName(), agent.getEmail(), agent.getCountry(), agent.getCity(),
                active, bookings.countByAgentIdAndBookingStatusIn(agent.getId(), PENDING), active);
    }

    private PeriodCounts withSupport(PeriodCounts counts, LocalDateTime start, LocalDateTime end) {
        return new PeriodCounts(counts.created(), counts.pending(), counts.assigned(), counts.completed(),
                counts.onsite(), counts.remote(),
                contacts.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(start, end));
    }

    private Map<String, Long> lifecycle() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (BookingStatus status : BookingStatus.values()) result.put(status.name(), 0L);
        bookings.countByBookingStatus().forEach(row -> result.put(row.getStatus().name(), row.getTotal()));
        return result;
    }

    private long countStatuses(Map<String, Long> counts, List<BookingStatus> statuses) {
        return statuses.stream().mapToLong(status -> counts.getOrDefault(status.name(), 0L)).sum();
    }

    private ModeCounts modeCounts(ServiceMode mode) {
        long total = bookings.countByServiceMode(mode);
        long pending = bookings.countByServiceModeAndBookingStatusIn(mode, PENDING);
        long assigned = bookings.countByServiceModeAndBookingStatusIn(mode, ASSIGNED);
        long completed = bookings.countByServiceModeAndBookingStatus(mode, BookingStatus.SERVICE_COMPLETED);
        return new ModeCounts(pending, assigned, assigned, completed, total);
    }
}
