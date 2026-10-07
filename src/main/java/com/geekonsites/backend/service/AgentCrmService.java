package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.AgentCrmDtos.*;
import com.geekonsites.backend.entity.*;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.repository.*;
import com.geekonsites.backend.repository.projection.CustomerActivity;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PHASE 9 — Agent CRM rewritten to be database-side and N+1-free.
 *
 * <p>The previous implementation called {@code findAll()} on users, bookings, contacts,
 * follow-ups and notes and then filtered/aggregated in Java (and issued a per-customer
 * notes query). This version:
 * <ul>
 *   <li>filters, searches and paginates customers in the database;</li>
 *   <li>loads the aggregate data for exactly the returned page in a fixed, small number
 *       of batched queries (independent of the number of customers);</li>
 *   <li>computes the CRM summary from count queries instead of loading whole tables.</li>
 * </ul>
 * Ordering changed from "most recent interaction first" to a stable id order, because the
 * interaction timestamp is an aggregate not available for ORDER BY in portable JPQL.
 */
@Service
@RequiredArgsConstructor
public class AgentCrmService {
    private final UserRepository users;
    private final BookingRepository bookings;
    private final ContactRepository contacts;
    private final AgentRepository agents;
    private final CrmNoteRepository notes;
    private final CrmFollowUpRepository followUps;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Page<CustomerRow> customers(String search, String country, String bookingStatus,
            String serviceMode, String followUpStatus, int page, int size) {
        int safePage = Math.max(0, page), safeSize = size <= 0 ? 20 : Math.min(100, size);
        String q = normalize(search);
        String countryFilter = blank(country) ? "" : country.trim();
        String bookingStatusFilter = blank(bookingStatus) ? "" : bookingStatus.trim().toUpperCase(Locale.ROOT);
        String serviceModeFilter = blank(serviceMode) ? "" : serviceMode.trim().toUpperCase(Locale.ROOT);
        String followUpFilter = blank(followUpStatus) ? "" : followUpStatus.trim().toUpperCase(Locale.ROOT);
        LocalDate today = LocalDate.now(clock);
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.plusDays(1).atStartOfDay();

        org.springframework.data.domain.Page<User> userPage = users.fetchCrmCustomers(
                q, countryFilter,
                bookingStatusFilter, parseEnum(BookingStatus.class, bookingStatusFilter),
                serviceModeFilter, parseEnum(ServiceMode.class, serviceModeFilter),
                followUpFilter, CrmFollowUp.Status.PENDING, todayStart, todayEnd,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id")));

        List<Long> ids = userPage.getContent().stream().map(User::getId).toList();
        if (ids.isEmpty()) {
            return new Page<>(List.of(), userPage.getNumber(), userPage.getSize(),
                    userPage.getTotalElements(), userPage.getTotalPages());
        }

        Map<Long, List<Booking>> byCustomer = bookings.findByCustomerIdInOrderByCreatedAtDesc(ids).stream()
                .filter(b -> b.getCustomerId() != null)
                .collect(Collectors.groupingBy(Booking::getCustomerId));
        Map<Long, List<CrmFollowUp>> byFollowUp = followUps.findByCustomerIdIn(ids).stream()
                .collect(Collectors.groupingBy(CrmFollowUp::getCustomerId));
        Map<Long, LocalDateTime> lastContact = contacts.lastContactByCustomer(ids).stream()
                .collect(Collectors.toMap(CustomerActivity::customerId, CustomerActivity::lastAt, (a, b) -> a));
        Map<Long, LocalDateTime> lastNote = notes.lastActivityByCustomer(ids).stream()
                .collect(Collectors.toMap(CustomerActivity::customerId, CustomerActivity::lastAt, (a, b) -> a));

        List<CustomerRow> rows = userPage.getContent().stream()
                .map(u -> row(u, byCustomer.getOrDefault(u.getId(), List.of()),
                        byFollowUp.getOrDefault(u.getId(), List.of()),
                        lastContact.get(u.getId()), lastNote.get(u.getId())))
                .toList();
        return new Page<>(rows, userPage.getNumber(), userPage.getSize(),
                userPage.getTotalElements(), userPage.getTotalPages());
    }

    @Transactional(readOnly = true)
    public CustomerDetail customer(Long id) {
        User u = customerUser(id);
        List<Booking> history = bookings.findByCustomerIdOrderByCreatedAtDesc(id);
        String address = history.stream().map(this::address).filter(s -> !blank(s)).findFirst().orElse(null);
        List<EnquiryRow> enquiryRows = contacts.findForCustomer(id, u.getEmail()).stream()
                .map(this::enquiry).toList();
        return new CustomerDetail(id, u.getFullName(), u.getEmail(), u.getPhone(), u.getCountry(), address,
                history.stream().map(this::booking).toList(), enquiryRows,
                notes.findByCustomerIdOrderByCreatedAtDesc(id).stream().map(this::note).toList(),
                followUps.findByCustomerIdOrderByFollowUpAtAsc(id).stream().map(this::followUp).toList());
    }

    @Transactional(readOnly = true)
    public List<EnquiryRow> enquiries() {
        List<ContactMessage> page = contacts
                .findAllByOrderByCreatedAtDesc(PageRequest.of(0, 200)).getContent();
        Set<String> emails = page.stream().map(c -> normalize(c.getEmail()))
                .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        Map<String, Long> customersByEmail = emails.isEmpty() ? Map.of()
                : users.findCustomersByEmails(emails).stream()
                        .collect(Collectors.toMap(u -> normalize(u.getEmail()), User::getId, (a, b) -> a));
        return page.stream().map(c -> new EnquiryRow(c.getId(),
                c.getCustomerId() != null ? c.getCustomerId() : customersByEmail.get(normalize(c.getEmail())),
                c.getFullName(), c.getEmail(), c.getPhone(), c.getCountry(), c.getSubject(), c.getMessage(),
                c.getStatus(), c.getCreatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.plusDays(1).atStartOfDay();
        long totalCustomers = users.countByRole(Role.CUSTOMER);
        long open = contacts.countOpen(List.of("RESOLVED", "CLOSED"));
        long active = bookings.countByBookingStatusNotIn(List.of(
                BookingStatus.CANCELLED, BookingStatus.BOOKING_CLOSED, BookingStatus.SERVICE_COMPLETED));
        long overdue = followUps.countByStatusAndFollowUpAtLessThan(CrmFollowUp.Status.PENDING, todayStart);
        long dueOrOverdue = followUps.countByStatusAndFollowUpAtLessThanEqual(CrmFollowUp.Status.PENDING, todayEnd);
        long dueToday = Math.max(0, dueOrOverdue - overdue);
        return new Summary(totalCustomers, open, active, dueToday, overdue);
    }

    @Transactional
    public NoteRow addNote(Long customerId, NoteRequest request, Authentication auth) {
        customerUser(customerId); Actor actor = actor(auth);
        CrmNote note = new CrmNote(); note.setCustomerId(customerId); note.setAgentId(actor.agentId());
        note.setAuthorName(actor.name()); note.setAuthorRole(actor.admin() ? "ADMIN" : "AGENT");
        note.setNoteText(request.noteText().trim()); return note(notes.save(note));
    }

    @Transactional
    public FollowUpRow addFollowUp(Long customerId, FollowUpRequest request, Authentication auth) {
        customerUser(customerId); Actor actor = actor(auth);
        CrmFollowUp f = new CrmFollowUp(); f.setCustomerId(customerId); f.setAgentId(actor.agentId());
        f.setOwnerName(actor.name()); f.setOwnerRole(actor.admin() ? "ADMIN" : "AGENT");
        f.setFollowUpAt(request.followUpAt()); f.setReason(request.reason().trim()); f.setInternalNote(request.internalNote());
        return followUp(followUps.save(f));
    }

    @Transactional
    public FollowUpRow complete(Long id, Authentication auth) {
        CrmFollowUp f = followUps.findById(id).orElseThrow(() -> new EntityNotFoundException("Follow-up not found"));
        Actor actor = actor(auth);
        if (!actor.admin() && !Objects.equals(f.getAgentId(), actor.agentId())) throw new AccessDeniedException("Follow-up belongs to another agent");
        if (f.getStatus() != CrmFollowUp.Status.PENDING) throw new IllegalStateException("Only pending follow-ups can be completed");
        f.setStatus(CrmFollowUp.Status.COMPLETED); f.setCompletedAt(LocalDateTime.now()); return followUp(followUps.save(f));
    }

    private CustomerRow row(User u, List<Booking> bs, List<CrmFollowUp> fs, LocalDateTime contactAt, LocalDateTime noteAt) {
        Booking latest = bs.stream().max(Comparator.comparing(Booking::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))).orElse(null);
        LocalDateTime bookingAt = latest == null ? null : latest.getCreatedAt();
        return new CustomerRow(u.getId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getCountry(), bs.size(), bookingAt,
                latest == null ? null : text(latest.getBookingStatus()), latest == null ? null : text(latest.getServiceMode()),
                latest == null ? null : latest.getTechnicianName(), latest == null ? null : latest.getPaymentStatus(),
                latest(latest(bookingAt, contactAt), noteAt), followUpState(fs));
    }
    private String followUpState(List<CrmFollowUp> fs) { return fs.stream().filter(f -> f.getStatus() == CrmFollowUp.Status.PENDING)
            .min(Comparator.comparing(CrmFollowUp::getFollowUpAt)).map(f -> timing(f.getFollowUpAt())).orElse("NONE"); }
    private String timing(LocalDateTime at) { LocalDate today=LocalDate.now(clock); return at.toLocalDate().isBefore(today)?"OVERDUE":at.toLocalDate().equals(today)?"DUE_TODAY":"UPCOMING"; }
    private BookingRow booking(Booking b) { return new BookingRow(b.getId(), b.getServiceType(), text(b.getServiceMode()), text(b.getBookingStatus()), b.getBookingDate(), b.getTechnicianName(), b.getPaymentStatus()); }
    private EnquiryRow enquiry(ContactMessage c) { return new EnquiryRow(c.getId(), c.getCustomerId(), c.getFullName(), c.getEmail(), c.getPhone(), c.getCountry(), c.getSubject(), c.getMessage(), c.getStatus(), c.getCreatedAt()); }
    private NoteRow note(CrmNote n) { return new NoteRow(n.getId(), n.getCustomerId(), n.getAgentId(), n.getAuthorName(), n.getNoteText(), n.getCreatedAt()); }
    private FollowUpRow followUp(CrmFollowUp f) { return new FollowUpRow(f.getId(), f.getCustomerId(), f.getAgentId(), f.getOwnerName(), f.getFollowUpAt(), f.getReason(), f.getStatus().name(), f.getInternalNote(), f.getCreatedAt(), f.getCompletedAt(), f.getStatus()==CrmFollowUp.Status.PENDING?timing(f.getFollowUpAt()):f.getStatus().name()); }
    private User customerUser(Long id) { User u=users.findById(id).orElseThrow(() -> new EntityNotFoundException("Customer not found")); if(u.getRole()!=Role.CUSTOMER) throw new EntityNotFoundException("Customer not found"); return u; }
    private Actor actor(Authentication auth) { boolean admin=auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")); if(admin) return new Actor(null, auth.getName(), true); Agent a=agents.findByEmail(auth.getName()).orElseThrow(() -> new AccessDeniedException("Agent profile not found")); return new Actor(a.getId(), a.getName(), false); }
    private String address(Booking b) { return String.join(", ", Arrays.asList(b.getAddress(),b.getCity(),b.getState(),b.getPostalCode(),b.getCountry()).stream().filter(s -> !blank(s)).toList()); }
    private LocalDateTime latest(LocalDateTime a, LocalDateTime b) { if(a==null)return b;if(b==null)return a;return a.isAfter(b)?a:b; }
    private String text(Object o){return o==null?null:o.toString();} private boolean blank(String s){return s==null||s.isBlank();}
    private String normalize(String s){return s==null?"":s.trim().toLowerCase(Locale.ROOT);}
    private <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        try { return Enum.valueOf(type, value); } catch (IllegalArgumentException invalid) { return null; }
    }
    private record Actor(Long agentId,String name,boolean admin){}
}
