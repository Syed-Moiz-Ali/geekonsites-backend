package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.AgentCrmDtos.*;
import com.geekonsites.backend.service.AgentCrmService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/agent-crm")
@RequiredArgsConstructor
public class AgentCrmController {
    private final AgentCrmService crm;
    @GetMapping("/customers") public Page<CustomerRow> customers(@RequestParam(defaultValue="") String search,
        @RequestParam(defaultValue="") String country,@RequestParam(defaultValue="") String bookingStatus,
        @RequestParam(defaultValue="") String serviceMode,@RequestParam(defaultValue="") String followUpStatus,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return crm.customers(search,country,bookingStatus,serviceMode,followUpStatus,page,size);
    }
    @GetMapping("/customers/{id}") public CustomerDetail customer(@PathVariable Long id){return crm.customer(id);}
    @GetMapping("/enquiries") public List<EnquiryRow> enquiries(){return crm.enquiries();}
    @GetMapping("/summary") public Summary summary(){return crm.summary();}
    @PostMapping("/customers/{id}/notes") public NoteRow note(@PathVariable Long id,@Valid @RequestBody NoteRequest r,Authentication a){return crm.addNote(id,r,a);}
    @PostMapping("/customers/{id}/follow-ups") public FollowUpRow followUp(@PathVariable Long id,@Valid @RequestBody FollowUpRequest r,Authentication a){return crm.addFollowUp(id,r,a);}
    @PutMapping("/follow-ups/{id}/complete") public FollowUpRow complete(@PathVariable Long id,Authentication a){return crm.complete(id,a);}
}
