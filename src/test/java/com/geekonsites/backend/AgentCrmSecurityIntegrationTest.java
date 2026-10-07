package com.geekonsites.backend;

import com.geekonsites.backend.dto.AgentCrmDtos;
import com.geekonsites.backend.entity.Agent;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.jwt.JwtService;
import com.geekonsites.backend.repository.AgentRepository;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.repository.UserRepository;
import com.geekonsites.backend.service.AgentCrmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {
        "spring.main.lazy-initialization=false",
        "spring.datasource.url=jdbc:h2:mem:crmsecurity;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.jwt.secret=crm-security-test-secret-key-with-at-least-32-characters",
        "firebase.enabled=false",
        "google.calendar.enabled=false"
})
@AutoConfigureMockMvc
class AgentCrmSecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AgentRepository agents;
    @Autowired BookingRepository bookings;
    @Autowired TechnicianRepository technicians;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;
    @MockBean AgentCrmService crm;

    private String agentToken;
    private String adminToken;
    private String customerToken;
    private String technicianToken;
    private Long agentId;

    @BeforeEach void setup() {
        bookings.deleteAll(); technicians.deleteAll(); agents.deleteAll(); users.deleteAll();
        User agent = user("agent-security@geekonsites.com", Role.AGENT);
        User admin = user("admin-security@geekonsites.com", Role.ADMIN);
        User customer = user("customer-security@example.com", Role.CUSTOMER);
        User technician = user("tech-security@geekonsites.com", Role.TECHNICIAN);
        agentToken = jwt.generateToken(agent); adminToken = jwt.generateToken(admin);
        customerToken = jwt.generateToken(customer); technicianToken = jwt.generateToken(technician);
        Agent profile = new Agent(); profile.setName("Security Agent"); profile.setEmail(agent.getEmail()); profile.setCountry("UK"); profile.setCity("London"); profile.setStatus("ACTIVE"); agentId=agents.save(profile).getId();

        when(crm.summary()).thenReturn(new AgentCrmDtos.Summary(0,0,0,0,0));
        when(crm.customers(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt(),anyInt()))
                .thenReturn(new AgentCrmDtos.Page<>(List.of(),0,20,0,0));
        when(crm.enquiries()).thenReturn(List.of());
        when(crm.customer(anyLong())).thenReturn(new AgentCrmDtos.CustomerDetail(1L,"Customer","customer@example.com",null,"US",null,List.of(),List.of(),List.of(),List.of()));
        when(crm.addNote(anyLong(),any(),any())).thenReturn(new AgentCrmDtos.NoteRow(1L,1L,1L,"Agent","Note",LocalDateTime.now()));
        when(crm.addFollowUp(anyLong(),any(),any())).thenReturn(new AgentCrmDtos.FollowUpRow(1L,1L,1L,"Agent",LocalDateTime.now().plusDays(1),"Call","PENDING",null,LocalDateTime.now(),null,"UPCOMING"));
        when(crm.complete(anyLong(),any())).thenReturn(new AgentCrmDtos.FollowUpRow(1L,1L,1L,"Agent",LocalDateTime.now(),"Call","COMPLETED",null,LocalDateTime.now(),LocalDateTime.now(),"COMPLETED"));
    }

    @Test void agentCanAccessEveryCrmOperationAndAgentNotifications() throws Exception {
        getOk("/api/agent-crm/summary",agentToken); getOk("/api/agent-crm/customers",agentToken);
        getOk("/api/agent-crm/customers/1",agentToken); getOk("/api/agent-crm/enquiries",agentToken);
        mvc.perform(post("/api/agent-crm/customers/1/notes").header("Authorization",bearer(agentToken)).contentType(MediaType.APPLICATION_JSON).content("{\"noteText\":\"Call completed\"}" )).andExpect(status().isOk());
        mvc.perform(post("/api/agent-crm/customers/1/follow-ups").header("Authorization",bearer(agentToken)).contentType(MediaType.APPLICATION_JSON).content("{\"followUpAt\":\"2099-01-01T10:00:00\",\"reason\":\"Call customer\"}" )).andExpect(status().isOk());
        mvc.perform(put("/api/agent-crm/follow-ups/1/complete").header("Authorization",bearer(agentToken))).andExpect(status().isOk());
        getOk("/api/agents/my-notifications",agentToken);
    }

    @Test void adminCanAccessCrm() throws Exception { getOk("/api/agent-crm/summary",adminToken); getOk("/api/agent-crm/customers",adminToken); }
    @Test void customerCannotAccessCrm() throws Exception { getForbidden(customerToken); }
    @Test void technicianCannotAccessCrm() throws Exception { getForbidden(technicianToken); }

    @Test void agentNotificationEndpointEnforcesAgentAuthorities() throws Exception {
        mvc.perform(get("/api/agents/my-notifications").header("Authorization", bearer(agentToken)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/agents/my-notifications").header("Authorization", bearer(customerToken)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/agents/my-notifications").header("Authorization", bearer(technicianToken)))
                .andExpect(status().isForbidden());
        // PHASE 7: unauthenticated is 401 (standardized).
        mvc.perform(get("/api/agents/my-notifications"))
                .andExpect(status().isUnauthorized());
    }

    @Test void realAgentLoginJwtAccessesEveryCrmRouteAndNotificationPolling() throws Exception {
        String realAgentToken = login("agent-security@geekonsites.com", "TestPass123!");
        org.junit.jupiter.api.Assertions.assertEquals("ROLE_AGENT",
                users.findByEmail("agent-security@geekonsites.com").orElseThrow()
                        .getAuthorities().iterator().next().getAuthority());

        for (String path : List.of("/api/agent-crm/summary", "/api/agent-crm/customers",
                "/api/agent-crm/customers/1", "/api/agent-crm/enquiries",
                "/api/agents/my-notifications")) {
            getOk(path, realAgentToken);
            assertForbiddenForNonAgents(path);
        }

        mvc.perform(post("/api/agent-crm/customers/1/notes")
                        .header("Authorization", bearer(realAgentToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"noteText\":\"Production auth check\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/agent-crm/customers/1/follow-ups")
                        .header("Authorization", bearer(realAgentToken)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"followUpAt\":\"2099-01-01T10:00:00\",\"reason\":\"Production auth check\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/agent-crm/follow-ups/1/complete")
                        .header("Authorization", bearer(realAgentToken)))
                .andExpect(status().isOk());
    }

    @Test void realAgentLoginJwtCanLoadGlobalAgentSupportFeed() throws Exception {
        String realAgentToken = login("agent-security@geekonsites.com", "TestPass123!");
        mvc.perform(get("/api/contact").header("Authorization", bearer(realAgentToken)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/contact").header("Authorization", bearer(customerToken)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/contact").header("Authorization", bearer(technicianToken)))
                .andExpect(status().isForbidden());
        // PHASE 7: unauthenticated is 401 (standardized).
        mvc.perform(get("/api/contact"))
                .andExpect(status().isUnauthorized());
    }

    @Test void agentDashboardSummaryUsesPersistedCountsAndServerDates() throws Exception {
        Booking pending = booking(BookingStatus.PENDING, ServiceMode.REMOTE, agentId);
        Booking assigned = booking(BookingStatus.TECHNICIAN_ASSIGNED, ServiceMode.ONSITE, agentId);
        Booking completedYesterday = booking(BookingStatus.SERVICE_COMPLETED, ServiceMode.ONSITE, null);
        LocalDateTime utcNow = LocalDateTime.now(java.time.Clock.systemUTC());
        completedYesterday.setCreatedAt(utcNow.minusDays(2));
        completedYesterday.setServiceCompletedAt(utcNow.minusDays(1));
        bookings.save(completedYesterday);
        technicians.save(technician("APPROVED","AVAILABLE"));
        technicians.save(technician("PENDING","AVAILABLE"));
        technicians.save(technician("APPROVED","BUSY"));

        mvc.perform(get("/api/agents/dashboard-summary").header("Authorization",bearer(agentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookings.total").value(3))
                .andExpect(jsonPath("$.bookings.pending").value(1))
                .andExpect(jsonPath("$.bookings.assigned").value(1))
                .andExpect(jsonPath("$.bookings.completed").value(1))
                .andExpect(jsonPath("$.bookings.remoteSessions").value(1))
                .andExpect(jsonPath("$.availableTechnicians").value(1))
                .andExpect(jsonPath("$.today.created").value(2))
                .andExpect(jsonPath("$.today.pending").value(1))
                .andExpect(jsonPath("$.today.assigned").value(1))
                .andExpect(jsonPath("$.today.completed").value(0))
                .andExpect(jsonPath("$.today.onsite").value(1))
                .andExpect(jsonPath("$.today.remote").value(1))
                .andExpect(jsonPath("$.yesterday.created").value(0))
                .andExpect(jsonPath("$.yesterday.completed").value(1))
                .andExpect(jsonPath("$.topMetrics.totalAgents").value(1))
                .andExpect(jsonPath("$.topMetrics.agentsWithActiveJobs").value(1))
                .andExpect(jsonPath("$.topMetrics.activeJobs").value(2))
                .andExpect(jsonPath("$.topMetrics.needsAttention").value(1))
                .andExpect(jsonPath("$.technicianOperations.available").value(1))
                .andExpect(jsonPath("$.bookingLifecycle.PENDING").value(1))
                .andExpect(jsonPath("$.workload.agentId").value(agentId))
                .andExpect(jsonPath("$.workload.assignedBookings").value(2))
                .andExpect(jsonPath("$.workload.actionNeeded").value(1))
                .andExpect(jsonPath("$.timezone").isString());
        mvc.perform(get("/api/agents/booking-queue?size=999").header("Authorization",bearer(agentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].remoteSessionLink").doesNotExist());
        mvc.perform(get("/api/agents/dashboard-summary").header("Authorization",bearer(adminToken))).andExpect(status().isOk());
        mvc.perform(get("/api/agents/dashboard-summary").header("Authorization",bearer(customerToken))).andExpect(status().isForbidden());
        mvc.perform(get("/api/agents/dashboard-summary").header("Authorization",bearer(technicianToken))).andExpect(status().isForbidden());
    }

    @Test void existingBookingAssignmentFlowStillWorks() throws Exception {
        Booking unassigned = booking(BookingStatus.PENDING, ServiceMode.ONSITE, null);

        mvc.perform(put("/api/agents/{agentId}/assign-booking/{bookingId}", agentId, unassigned.getId())
                        .header("Authorization", bearer(agentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agentId))
                .andExpect(jsonPath("$.bookingStatus").value("PENDING"));

        Booking persisted = bookings.findById(unassigned.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(agentId, persisted.getAgentId());
        org.junit.jupiter.api.Assertions.assertEquals(BookingStatus.PENDING, persisted.getBookingStatus());
    }

    private Booking booking(BookingStatus status, ServiceMode mode, Long owner) {
        Booking b=new Booking();b.setCustomerId(3L);b.setCustomerName("Customer");b.setServiceType("Support");b.setServiceMode(mode);b.setBookingStatus(status);b.setAgentId(owner);
        Booking saved = bookings.saveAndFlush(b);
        saved.setCreatedAt(LocalDateTime.now(java.time.Clock.systemUTC()));
        return bookings.saveAndFlush(saved);
    }
    private Technician technician(String verification,String availability) {
        Technician t=new Technician();t.setName(verification+availability);t.setEmail(verification.toLowerCase()+availability.toLowerCase()+"@example.com");t.setPhone("+1"+Math.abs((verification+availability).hashCode()));t.setVerificationStatus(verification);t.setAvailabilityStatus(availability);return t;
    }

    private User user(String email, Role role) { User u=new User();u.setFullName(role.name());u.setEmail(email);u.setPassword(passwordEncoder.encode("TestPass123!"));u.setRole(role);return users.save(u); }
    private String login(String email, String password) throws Exception {
        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }
    private void assertForbiddenForNonAgents(String path) throws Exception {
        mvc.perform(get(path).header("Authorization", bearer(customerToken))).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization", bearer(technicianToken))).andExpect(status().isForbidden());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }
    private void getOk(String path,String token) throws Exception { mvc.perform(get(path).header("Authorization",bearer(token))).andExpect(status().isOk()); }
    private void getForbidden(String token) throws Exception { mvc.perform(get("/api/agent-crm/summary").header("Authorization",bearer(token))).andExpect(status().isForbidden()); }
    private String bearer(String token){return "Bearer "+token;}
}
