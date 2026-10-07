package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.AdminRefundDecisionDto;
import com.geekonsites.backend.entity.RefundRequest;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.service.RefundService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/refunds")
public class AdminRefundController {
    private final RefundService refundService;

    public AdminRefundController(RefundService refundService) { this.refundService = refundService; }

    @GetMapping
    public ResponseEntity<com.geekonsites.backend.dto.PageResponse<com.geekonsites.backend.dto.RefundResponse>> all(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        org.springframework.data.domain.Pageable pageable = com.geekonsites.backend.dto.PageRequestParams.of(page, size,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "requestedAt"));
        return ResponseEntity.ok(com.geekonsites.backend.dto.PageResponse.of(
                refundService.getAll(pageable), com.geekonsites.backend.dto.RefundResponse::from));
    }

    @PutMapping("/{id}/review")
    public ResponseEntity<RefundRequest> review(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(refundService.review(id, principal(auth)));
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<RefundRequest> execute(@PathVariable Long id,
                                                  @Valid @RequestBody AdminRefundDecisionDto request,
                                                  Authentication auth) {
        return ResponseEntity.ok(refundService.approveAndExecute(id, request, principal(auth)));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<RefundRequest> reject(@PathVariable Long id,
                                                 @Valid @RequestBody AdminRefundDecisionDto request,
                                                 Authentication auth) {
        return ResponseEntity.ok(refundService.reject(id, request, principal(auth)));
    }

    private User principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) throw new RuntimeException("Authentication required");
        return user;
    }
}
