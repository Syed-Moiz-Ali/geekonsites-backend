package com.geekonsites.backend.service;

import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * PHASE 4 — runs non-critical external side effects only AFTER the business transaction
 * has committed, so a provider/email failure can never roll back committed state and no
 * external call happens before commit.
 *
 * <p>{@code fallbackExecution = true} keeps behaviour when an event is published outside a
 * transaction (e.g. direct service calls in tests).
 */
@Component
public class ExternalSideEffectListener {

    private static final Logger log = LoggerFactory.getLogger(ExternalSideEffectListener.class);

    private final RemoteSessionProvisioningService remoteSessionProvisioningService;
    private final NotificationService notificationService;
    private final BookingRepository bookingRepository;
    private final TechnicianRepository technicianRepository;
    private final ExcessReversalService excessReversalService;

    public ExternalSideEffectListener(
            RemoteSessionProvisioningService remoteSessionProvisioningService,
            NotificationService notificationService,
            BookingRepository bookingRepository,
            TechnicianRepository technicianRepository,
            ExcessReversalService excessReversalService
    ) {
        this.remoteSessionProvisioningService = remoteSessionProvisioningService;
        this.notificationService = notificationService;
        this.bookingRepository = bookingRepository;
        this.technicianRepository = technicianRepository;
        this.excessReversalService = excessReversalService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRemoteProvisionRequested(RemoteSessionProvisionRequestedEvent event) {
        try {
            remoteSessionProvisioningService.provisionAfterPayment(event.bookingId());
        } catch (RuntimeException exception) {
            log.warn("Post-commit remote provisioning failed for bookingId={}", event.bookingId(), exception);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onExcessReversalRequested(ExcessPaymentReversalRequestedEvent event) {
        try {
            excessReversalService.reverse(event.paymentTransactionId());
        } catch (RuntimeException exception) {
            log.warn("Post-commit excess reversal dispatch failed for paymentTransactionId={}",
                    event.paymentTransactionId(), exception);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBookingAssigned(BookingAssignedEvent event) {
        try {
            bookingRepository.findById(event.bookingId()).ifPresent(booking -> {
                technicianRepository.findById(event.technicianId()).ifPresent(technician -> {
                    notificationService.createNotification(
                            booking.getCustomerId(),
                            com.geekonsites.backend.enums.NotificationType.TECHNICIAN_ASSIGNED,
                            "Technician Assigned",
                            technician.getName() + " has been assigned to your booking.",
                            "TECHNICIAN_ASSIGNED:" + booking.getId() + ":" + technician.getId());
                    notificationService.createTechnicianNotification(
                            technician.getId(),
                            com.geekonsites.backend.enums.NotificationType.TECHNICIAN_ASSIGNED,
                            "New Job Assigned",
                            "You have been assigned to booking GOS-" + booking.getId()
                                    + " (" + booking.getServiceType() + ").",
                            "TECHNICIAN_ASSIGNED:" + booking.getId() + ":" + technician.getId());
                });
                try {
                    remoteSessionProvisioningService.syncAssignedParticipantsBeforeStart(booking);
                } catch (RuntimeException exception) {
                    log.warn("Post-commit calendar attendee sync failed for bookingId={}", event.bookingId(), exception);
                }
            });
        } catch (RuntimeException exception) {
            log.warn("Post-commit assignment side effects failed for bookingId={}", event.bookingId(), exception);
        }
    }
}
