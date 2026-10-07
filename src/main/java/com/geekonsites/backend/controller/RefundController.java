package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.RefundRequestCreateDto;
import com.geekonsites.backend.entity.RefundRequest;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.service.RefundService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/refunds")
public class RefundController {
    private final RefundService refundService;

    public RefundController(RefundService refundService) { this.refundService = refundService; }

    @PostMapping("/bookings/{bookingId}")
    public ResponseEntity<RefundRequest> request(@PathVariable Long bookingId,
                                                 @Valid @RequestBody RefundRequestCreateDto request,
                                                 Authentication authentication) {
        return ResponseEntity.ok(refundService.requestRefund(bookingId, request, principal(authentication)));
    }

    @GetMapping("/my-refunds")
    public ResponseEntity<com.geekonsites.backend.dto.PageResponse<com.geekonsites.backend.dto.RefundResponse>> mine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        org.springframework.data.domain.Pageable pageable = com.geekonsites.backend.dto.PageRequestParams.of(page, size,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "requestedAt"));
        return ResponseEntity.ok(com.geekonsites.backend.dto.PageResponse.of(
                refundService.getCustomerRefunds(principal(authentication), pageable),
                com.geekonsites.backend.dto.RefundResponse::from));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RefundRequest> one(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(refundService.getCustomerRefund(id, principal(authentication)));
    }

    private User principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) throw new RuntimeException("Authentication required");
        return user;
    }
}
