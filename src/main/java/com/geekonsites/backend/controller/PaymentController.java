package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.StripeCheckoutRequest;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * PHASE 7 — no controller-wide catch-all. Expected business errors are signalled with
 * ResponseStatusException and rendered by the global handler; unexpected failures fall
 * through to a 500. The Stripe webhook keeps provider-specific semantics.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/create-checkout-session")
    public ResponseEntity<?> createCheckoutSession(
            @Valid @RequestBody StripeCheckoutRequest request,
            Authentication authentication
    ) {
        User customer = authenticatedCustomer(authentication);
        return ResponseEntity.ok(
                paymentService.createCheckoutSession(
                        request.getBookingId(),
                        request.getPaymentType(),
                        customer.getId(),
                        request.getUkEarlyServiceConsent()
                )
        );
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> handleStripeWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader
    ) {
        // Invalid signature → 400 (handled by the service/global handler with no mutation).
        // Unsupported valid events are acknowledged; transient processing failures surface as 5xx
        // so Stripe can retry safely.
        paymentService.handleWebhook(payload, sigHeader);
        return ResponseEntity.ok("Webhook received");
    }

    @GetMapping("/confirm-checkout-session")
    public ResponseEntity<?> confirmCheckoutSession(
            @RequestParam String sessionId,
            Authentication authentication
    ) {
        User customer = authenticatedCustomer(authentication);
        return ResponseEntity.ok(paymentService.confirmCheckoutSession(sessionId, customer.getId()));
    }

    private User authenticatedCustomer(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User customer)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return customer;
    }
}
