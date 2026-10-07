package com.geekonsites.backend.controller;

import com.geekonsites.backend.dto.AdminOperationsDtos.FailureItem;
import com.geekonsites.backend.dto.AdminOperationsDtos.FailuresResponse;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.PaymentTransaction;
import com.geekonsites.backend.enums.PaymentReversalStatus;
import com.geekonsites.backend.enums.PaymentRefundStatus;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.PaymentRefundRepository;
import com.geekonsites.backend.repository.PaymentTransactionRepository;
import com.geekonsites.backend.service.ExcessReversalService;
import com.geekonsites.backend.service.RemoteSessionProvisioningService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PHASE 9 — minimal ADMIN-only operational failure visibility and manual retry.
 *
 * <p>Manual retry calls the SAME idempotent recovery services used by the scheduled
 * worker, so repeated clicks are safe. Raw provider errors/secrets are never exposed.
 */
@RestController
@RequestMapping("/api/admin/operations")
public class AdminOperationsController {

    private static final int MAX_ITEMS = 50;

    private final PaymentTransactionRepository paymentTransactions;
    private final BookingRepository bookings;
    private final PaymentRefundRepository paymentRefunds;
    private final ExcessReversalService excessReversalService;
    private final RemoteSessionProvisioningService remoteSessionProvisioningService;

    public AdminOperationsController(
            PaymentTransactionRepository paymentTransactions,
            BookingRepository bookings,
            PaymentRefundRepository paymentRefunds,
            ExcessReversalService excessReversalService,
            RemoteSessionProvisioningService remoteSessionProvisioningService) {
        this.paymentTransactions = paymentTransactions;
        this.bookings = bookings;
        this.paymentRefunds = paymentRefunds;
        this.excessReversalService = excessReversalService;
        this.remoteSessionProvisioningService = remoteSessionProvisioningService;
    }

    @GetMapping("/failures")
    public ResponseEntity<FailuresResponse> failures() {
        List<FailureItem> items = new ArrayList<>();
        for (PaymentTransaction transaction : paymentTransactions
                .findByExcessTrueAndReversalStatusNotOrderByIdAsc(PaymentReversalStatus.SUCCEEDED, PageRequest.of(0, MAX_ITEMS))) {
            items.add(new FailureItem(transaction.getId(), "EXCESS_REVERSAL",
                    transaction.getReversalStatus() == null ? null : transaction.getReversalStatus().name(),
                    transaction.getReversalAttempts(), transaction.getReversalNextAttemptAt(),
                    transaction.getReversalError()));
        }
        for (Booking booking : bookings.findByRemoteSessionStatusOrderByIdAsc("FAILED", PageRequest.of(0, MAX_ITEMS))) {
            items.add(new FailureItem(booking.getId(), "REMOTE_PROVISIONING", booking.getRemoteSessionStatus(),
                    booking.getRemoteProvisioningAttempts(), booking.getRemoteProvisioningNextAttemptAt(),
                    booking.getRemoteSessionProvisioningError()));
        }
        long excess = paymentTransactions.countByExcessTrueAndReversalStatusNot(PaymentReversalStatus.SUCCEEDED);
        long remote = bookings.countByRemoteSessionStatus("FAILED");
        long pendingRefunds = paymentRefunds.countByStatus(PaymentRefundStatus.PENDING);
        return ResponseEntity.ok(new FailuresResponse(items, excess, remote, pendingRefunds));
    }

    @PostMapping("/retry/{type}/{id}")
    public ResponseEntity<Void> retry(@PathVariable String type, @PathVariable Long id) {
        switch (type.trim().toUpperCase(Locale.ROOT)) {
            case "EXCESS_REVERSAL" -> excessReversalService.reverse(id);
            case "REMOTE_PROVISIONING" -> remoteSessionProvisioningService.provisionAfterPayment(id);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported operation type; expected EXCESS_REVERSAL or REMOTE_PROVISIONING");
        }
        return ResponseEntity.accepted().build();
    }
}
