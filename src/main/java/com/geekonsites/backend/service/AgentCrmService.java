package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.AgentCrmDtos.*;
import com.geekonsites.backend.entity.*;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.repository.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentCrmService {
    private final UserRepository users;
    private final BookingRepository bookings;
    private final ContactRepository contacts;
    private final AgentRepository agents;
    private final CrmNoteRepository notes;
    private final CrmFollowUpRepository followUps;

    @Transactional(readOnly = true)
    public Page<CustomerRow> customers(String search, String country, String bookingStatus,
            String serviceMode, String followUpStatus, int page, int size) {
        int safePage = Math.max(0, page), safeSize = Math.min(100, Math.max(1, size));
        Map<Long,List<Booking>> byCustomer = bookings.findAll().stream()
                .filter(b -> b.getCustomerId() != null).collect(Collectors.groupingBy(Booking::getCustomerId));
        Map<Long,List<CrmFollowUp>> byFollowUp = followUps.findAll().stream()
                .collect(Collectors.groupingBy(CrmFollowUp::getCustomerId));
        Map<Long,LocalDateTime> lastContact = contacts.findAll().stream()
                .filter(c -> c.getCustomerId() != null).collect(Collectors.toMap(ContactMessage::getCustomerId,
                        ContactMessage::getCreatedAt, this::latest));
        String q = normalize(search);
        List<CustomerRow> rows = users.findAll().stream().filter(u -> u.getRole() == Role.CUSTOMER)
                .map(u -> row(u, byCustomer.getOrDefault(u.getId(), List.of()),
                        byFollowUp.getOrDefault(u.getId(), List.of()), lastContact.get(u.getId())))
                .filter(r -> q.isEmpty() || contains(r.name(), q) || contains(r.email(), q) || contains(r.phone(), q))
                .filter(r -> blank(country) || eq(r.country(), country))
                .filter(r -> blank(bookingStatus) || eq(r.latestBookingStatus(), bookingStatus))
                .filter(r -> blank(serviceMode) || eq(r.serviceMode(), serviceMode))
                .filter(r -> blank(followUpStatus) || eq(r.followUpStatus(), followUpStatus))
                .sorted(Comparator.comparing(CustomerRow::lastInteractionDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int from = Math.min(rows.size(), safePage * safeSize), to = Math.min(rows.size(), from + safeSize);
        return new Page<>(rows.subList(from, to), safePage, safeSize, rows.size(),
                rows.isEmpty() ? 0 : (int)Math.ceil((double)rows.size()/safeSize));
    }

    @Transactional(readOnly = true)
    public CustomerDetail customer(Long id) {
        User u = customerUser(id);
        List<Booking> history = bookings.findByCustomerIdOrderByCreatedAtDesc(id);
        String address = history.stream().map(this::address).filter(s -> !blank(s)).findFirst().orElse(null);
        List<EnquiryRow> enquiryRows = contacts.findAllByOrderByCreatedAtDesc().stream()
                .filter(c -> Objects.equals(c.getCustomerId(), id) || eq(c.getEmail(), u.getEmail()))
                .map(this::enquiry).toList();
        return new CustomerDetail(id, u.getFullName(), u.getEmail(), u.getPhone(), u.getCountry(), address,
                history.stream().map(this::booking).toList(), enquiryRows,
                notes.findByCustomerIdOrderByCreatedAtDesc(id).stream().map(this::note).toList(),
                followUps.findByCustomerIdOrderByFollowUpAtAsc(id).stream().map(this::followUp).toList());
    }

    @Transactional(readOnly = true)
    public List<EnquiryRow> enquiries() {
        Map<String,Long> customersByEmail = users.findAll().stream().filter(u -> u.getRole() == Role.CUSTOMER)
                .collect(Collectors.toMap(u -> normalize(u.getEmail()), User::getId, (a,b) -> a));
        return contacts.findAllByOrderByCreatedAtDesc().stream().map(c -> new EnquiryRow(c.getId(),
                c.getCustomerId() != null ? c.getCustomerId() : customersByEmail.get(normalize(c.getEmail())),
                c.getFullName(), c.getEmail(), c.getPhone(), c.getCountry(), c.getSubject(), c.getMessage(),
                c.getStatus(), c.getCreatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        LocalDate today = LocalDate.now();
        List<CrmFollowUp> all = followUps.findAll();
        long active = bookings.findAll().stream().filter(b -> b.getBookingStatus() != BookingStatus.CANCELLED &&
                b.getBookingStatus() != BookingStatus.BOOKING_CLOSED && b.getBookingStatus() != BookingStatus.SERVICE_COMPLETED).count();
        long open = contacts.findAll().stream().filter(c -> !"RESOLVED".equalsIgnoreCase(c.getStatus()) &&
                !"CLOSED".equalsIgnoreCase(c.getStatus())).count();
        return new Summary(users.findAll().stream().filter(u -> u.getRole() == Role.CUSTOMER).count(), open, active,
                all.stream().filter(f -> f.getStatus() == CrmFollowUp.Status.PENDING && f.getFollowUpAt().toLocalDate().equals(today)).count(),
                all.stream().filter(f -> f.getStatus() == CrmFollowUp.Status.PENDING && f.getFollowUpAt().isBefore(today.atStartOfDay())).count());
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

    private CustomerRow row(User u, List<Booking> bs, List<CrmFollowUp> fs, LocalDateTime contactAt) {
        Booking latest = bs.stream().max(Comparator.comparing(Booking::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))).orElse(null);
        LocalDateTime bookingAt = latest == null ? null : latest.getCreatedAt();
        LocalDateTime noteAt = notes.findByCustomerIdOrderByCreatedAtDesc(u.getId()).stream().map(CrmNote::getCreatedAt).findFirst().orElse(null);
        return new CustomerRow(u.getId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getCountry(), bs.size(), bookingAt,
                latest == null ? null : text(latest.getBookingStatus()), latest == null ? null : text(latest.getServiceMode()),
                latest == null ? null : latest.getTechnicianName(), latest == null ? null : latest.getPaymentStatus(),
                latest(latest(bookingAt, contactAt), noteAt), followUpState(fs));
    }
    private String followUpState(List<CrmFollowUp> fs) { return fs.stream().filter(f -> f.getStatus() == CrmFollowUp.Status.PENDING)
            .min(Comparator.comparing(CrmFollowUp::getFollowUpAt)).map(f -> timing(f.getFollowUpAt())).orElse("NONE"); }
    private String timing(LocalDateTime at) { LocalDate today=LocalDate.now(); return at.toLocalDate().isBefore(today)?"OVERDUE":at.toLocalDate().equals(today)?"DUE_TODAY":"UPCOMING"; }
    private BookingRow booking(Booking b) { return new BookingRow(b.getId(), b.getServiceType(), text(b.getServiceMode()), text(b.getBookingStatus()), b.getBookingDate(), b.getTechnicianName(), b.getPaymentStatus()); }
    private EnquiryRow enquiry(ContactMessage c) { return new EnquiryRow(c.getId(), c.getCustomerId(), c.getFullName(), c.getEmail(), c.getPhone(), c.getCountry(), c.getSubject(), c.getMessage(), c.getStatus(), c.getCreatedAt()); }
    private NoteRow note(CrmNote n) { return new NoteRow(n.getId(), n.getCustomerId(), n.getAgentId(), n.getAuthorName(), n.getNoteText(), n.getCreatedAt()); }
    private FollowUpRow followUp(CrmFollowUp f) { return new FollowUpRow(f.getId(), f.getCustomerId(), f.getAgentId(), f.getOwnerName(), f.getFollowUpAt(), f.getReason(), f.getStatus().name(), f.getInternalNote(), f.getCreatedAt(), f.getCompletedAt(), f.getStatus()==CrmFollowUp.Status.PENDING?timing(f.getFollowUpAt()):f.getStatus().name()); }
    private User customerUser(Long id) { User u=users.findById(id).orElseThrow(() -> new EntityNotFoundException("Customer not found")); if(u.getRole()!=Role.CUSTOMER) throw new EntityNotFoundException("Customer not found"); return u; }
    private Actor actor(Authentication auth) { boolean admin=auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")); if(admin) return new Actor(null, auth.getName(), true); Agent a=agents.findByEmail(auth.getName()).orElseThrow(() -> new AccessDeniedException("Agent profile not found")); return new Actor(a.getId(), a.getName(), false); }
    private String address(Booking b) { return String.join(", ", Arrays.asList(b.getAddress(),b.getCity(),b.getState(),b.getPostalCode(),b.getCountry()).stream().filter(s -> !blank(s)).toList()); }
    private LocalDateTime latest(LocalDateTime a, LocalDateTime b) { if(a==null)return b;if(b==null)return a;return a.isAfter(b)?a:b; }
    private String text(Object o){return o==null?null:o.toString();} private boolean blank(String s){return s==null||s.isBlank();}
    private String normalize(String s){return s==null?"":s.trim().toLowerCase(Locale.ROOT);} private boolean contains(String s,String q){return normalize(s).contains(q);} private boolean eq(String a,String b){return normalize(a).equals(normalize(b));}
    private record Actor(Long agentId,String name,boolean admin){}
}
