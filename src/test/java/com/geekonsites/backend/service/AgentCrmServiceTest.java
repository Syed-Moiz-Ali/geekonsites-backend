package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.AgentCrmDtos.FollowUpRequest;
import com.geekonsites.backend.dto.AgentCrmDtos.NoteRequest;
import com.geekonsites.backend.entity.*;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentCrmServiceTest {
    private UserRepository users; private BookingRepository bookings; private ContactRepository contacts;
    private AgentRepository agents; private CrmNoteRepository notes; private CrmFollowUpRepository followUps;
    private AgentCrmService service;

    @BeforeEach void setup() {
        users=mock(UserRepository.class); bookings=mock(BookingRepository.class); contacts=mock(ContactRepository.class);
        agents=mock(AgentRepository.class); notes=mock(CrmNoteRepository.class); followUps=mock(CrmFollowUpRepository.class);
        service=new AgentCrmService(users,bookings,contacts,agents,notes,followUps, Clock.systemUTC());
        when(users.fetchCrmCustomers(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any()))
                .thenReturn(emptyPage());
        when(bookings.findByCustomerIdInOrderByCreatedAtDesc(anyCollection())).thenReturn(List.of());
        when(followUps.findByCustomerIdIn(anyCollection())).thenReturn(List.of());
        when(contacts.lastContactByCustomer(anyCollection())).thenReturn(List.of());
        when(notes.lastActivityByCustomer(anyCollection())).thenReturn(List.of());
        when(notes.findByCustomerIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
    }

    @Test void customerSearchFiltersInDatabaseAndReturnsOnlySafeCustomerDto() {
        User customer=customer(7L,"Alice Example","alice@example.com");
        when(users.fetchCrmCustomers(eq("alice"),eq("US"),eq(""),any(),eq(""),any(),eq(""),any(),any(),any(),any()))
                .thenReturn(new PageImpl<>(List.of(customer), PageRequest.of(0,20), 1));

        var result=service.customers("alice","US","","","",0,20);

        assertEquals(1,result.totalElements()); assertEquals("alice@example.com",result.content().get(0).email());
        verify(users, never()).findAll();
    }

    @Test void customerListIssuesAFixedNumberOfQueriesRegardlessOfPageSize() {
        List<User> page = List.of(customer(1L,"A","a@x.com"), customer(2L,"B","b@x.com"), customer(3L,"C","c@x.com"));
        when(users.fetchCrmCustomers(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any()))
                .thenReturn(new PageImpl<>(page, PageRequest.of(0,20), 3));

        service.customers("","","","","",0,20);

        // No N+1: exactly one query per aggregate, independent of the 3 customers.
        verify(bookings, times(1)).findByCustomerIdInOrderByCreatedAtDesc(anyCollection());
        verify(followUps, times(1)).findByCustomerIdIn(anyCollection());
        verify(contacts, times(1)).lastContactByCustomer(anyCollection());
        verify(notes, times(1)).lastActivityByCustomer(anyCollection());
        verify(users, never()).findAll();
        verify(bookings, never()).findAll();
    }

    @Test void noteIsAppendOnlyAndAgentIdentityComesFromAuthentication() {
        when(users.findById(7L)).thenReturn(Optional.of(customer(7L,"Alice","alice@example.com")));
        Agent agent=new Agent(); agent.setId(3L); agent.setName("Ava Agent"); agent.setEmail("agent@geekonsites.com");
        when(agents.findByEmail(agent.getEmail())).thenReturn(Optional.of(agent)); when(notes.save(any())).thenAnswer(i->i.getArgument(0));
        var saved=service.addNote(7L,new NoteRequest("Called customer"),auth(agent.getEmail(),"ROLE_AGENT"));
        assertEquals(3L,saved.agentId()); assertEquals("Called customer",saved.noteText()); verify(notes).save(any(CrmNote.class));
    }

    @Test void followUpCompletionRejectsCrossAgentAccess() {
        Agent current=new Agent(); current.setId(4L); current.setName("Other"); current.setEmail("other@geekonsites.com");
        when(agents.findByEmail(current.getEmail())).thenReturn(Optional.of(current));
        CrmFollowUp followUp=new CrmFollowUp(); followUp.setId(10L); followUp.setAgentId(3L); followUp.setStatus(CrmFollowUp.Status.PENDING);
        when(followUps.findById(10L)).thenReturn(Optional.of(followUp));
        assertThrows(AccessDeniedException.class,()->service.complete(10L,auth(current.getEmail(),"ROLE_AGENT")));
        verify(followUps,never()).save(any());
    }

    @Test void overdueFollowUpIsCalculatedFromServerTimeUsingCountQuery() {
        when(followUps.countByStatusAndFollowUpAtLessThan(eq(CrmFollowUp.Status.PENDING), any())).thenReturn(1L);
        when(followUps.countByStatusAndFollowUpAtLessThanEqual(eq(CrmFollowUp.Status.PENDING), any())).thenReturn(1L);
        assertEquals(1,service.summary().overdueFollowUps());
        verify(followUps, never()).findAll();
    }

    @Test void followUpCreationRequiresCustomerAndDoesNotAcceptAgentIdFromRequest() {
        when(users.findById(7L)).thenReturn(Optional.of(customer(7L,"Alice","alice@example.com")));
        Agent agent=new Agent(); agent.setId(3L); agent.setName("Ava"); agent.setEmail("agent@geekonsites.com");
        when(agents.findByEmail(agent.getEmail())).thenReturn(Optional.of(agent)); when(followUps.save(any())).thenAnswer(i->i.getArgument(0));
        var result=service.addFollowUp(7L,new FollowUpRequest(LocalDateTime.now().plusDays(1),"Check service","Internal"),auth(agent.getEmail(),"ROLE_AGENT"));
        assertEquals(3L,result.agentId()); assertEquals("PENDING",result.status());
    }

    @Test void adminCanCreateInternalNoteWithoutFabricatingAgentIdentity() {
        when(users.findById(7L)).thenReturn(Optional.of(customer(7L,"Alice","alice@example.com")));
        when(notes.save(any())).thenAnswer(i->i.getArgument(0));
        var result=service.addNote(7L,new NoteRequest("Admin review"),auth("admin@geekonsites.com","ROLE_ADMIN"));
        assertNull(result.agentId()); assertEquals("admin@geekonsites.com",result.authorName()); verifyNoInteractions(agents);
    }

    @Test void publicCrmDtosContainNoSensitiveOrSessionFields() {
        var names=java.util.Arrays.stream(com.geekonsites.backend.dto.AgentCrmDtos.CustomerDetail.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertFalse(names.contains("password")); assertFalse(names.contains("paymentTransactionId"));
        assertFalse(names.contains("remoteSessionLink")); assertFalse(names.contains("identityDocumentData"));
    }

    private Page<User> emptyPage() { return new PageImpl<>(List.of(), PageRequest.of(0,20), 0); }
    private User customer(Long id,String name,String email){User u=new User();u.setId(id);u.setFullName(name);u.setEmail(email);u.setPhone("123");u.setCountry("US");u.setRole(Role.CUSTOMER);return u;}
    private Authentication auth(String email,String role){return new UsernamePasswordAuthenticationToken(email,"",List.of(new SimpleGrantedAuthority(role)));}
}
