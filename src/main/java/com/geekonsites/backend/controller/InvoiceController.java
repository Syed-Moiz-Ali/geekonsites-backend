package com.geekonsites.backend.controller;

import com.geekonsites.backend.entity.Invoice;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.service.InvoiceService;
import com.geekonsites.backend.service.BookingService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final BookingService bookingService;

    public InvoiceController(
            InvoiceService invoiceService,
            BookingService bookingService
    ) {
        this.invoiceService = invoiceService;
        this.bookingService = bookingService;
    }

    @PostMapping("/booking/{bookingId}")
    public ResponseEntity<Invoice> generateInvoiceFromBooking(
            @PathVariable("bookingId") Long bookingId,
            Authentication authentication
    ) {
        User user = authenticatedUser(authentication);
        bookingService.getBookingForCurrentUser(bookingId, user);
        Invoice invoice = invoiceService.generateInvoiceFromBooking(bookingId);
        verifyAccess(invoice, user);
        return ResponseEntity.ok(invoice);
    }

    @GetMapping("/{invoiceId}")
    public ResponseEntity<Invoice> getInvoiceById(
            @PathVariable("invoiceId") Long invoiceId,
            Authentication authentication
    ) {
        Invoice invoice = invoiceService.getInvoiceById(invoiceId);
        verifyAccess(invoice, authenticatedUser(authentication));
        return ResponseEntity.ok(invoice);
    }

    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<Invoice> getInvoiceByBookingId(
            @PathVariable("bookingId") Long bookingId,
            Authentication authentication
    ) {
        Invoice invoice = invoiceService.getInvoiceByBookingId(bookingId);
        verifyAccess(invoice, authenticatedUser(authentication));
        return ResponseEntity.ok(invoice);
    }

    private User authenticatedUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return user;
    }

    private void verifyAccess(Invoice invoice, User user) {
        if (user.getRole() == Role.ADMIN || user.getRole() == Role.AGENT) return;
        if (user.getRole() == Role.CUSTOMER && user.getId().equals(invoice.getCustomerId())) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not allowed to access this invoice");
    }
}
