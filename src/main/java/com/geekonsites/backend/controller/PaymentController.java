package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.StripeCheckoutRequest;
import com.geekonsites.backend.dto.StripeCheckoutResponse;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

   @PostMapping("/create-checkout-session")
public ResponseEntity<?> createCheckoutSession(
        @RequestBody StripeCheckoutRequest request,
        Authentication authentication
) {
    try {
        if (authentication == null || !(authentication.getPrincipal() instanceof User customer)) {
            return ResponseEntity.status(401).body(java.util.Map.of("message", "Authentication required"));
        }

        return ResponseEntity.ok(
                paymentService.createCheckoutSession(
                        request.getBookingId(),
                        request.getPaymentType(),
                        customer.getId(),
                        request.getUkEarlyServiceConsent()
                )
        );
    } catch (Exception e) {
        return ResponseEntity
                .status(500)
                .body(java.util.Map.of("message", "Unable to prepare payment. Please try again."));
    }
}

    @PostMapping("/webhook")
    public ResponseEntity<String> handleStripeWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader
    ) {
        paymentService.handleWebhook(payload, sigHeader);
        return ResponseEntity.ok("Webhook received");
    }

    /**
     * Confirms the Checkout Session after Stripe redirects the customer back to
     * GeekOnSites. This is deliberately authenticated and verifies that the
     * session metadata belongs to the signed-in customer before updating a
     * booking.
     */
    @GetMapping("/confirm-checkout-session")
    public ResponseEntity<?> confirmCheckoutSession(
            @RequestParam String sessionId,
            Authentication authentication
    ) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User customer)) {
            return ResponseEntity.status(401).body(java.util.Map.of("message", "Authentication required"));
        }

        return ResponseEntity.ok(
                paymentService.confirmCheckoutSession(sessionId, customer.getId())
        );
    }
}
