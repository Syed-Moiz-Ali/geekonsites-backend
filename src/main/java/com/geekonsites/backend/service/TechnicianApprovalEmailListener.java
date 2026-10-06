package com.geekonsites.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the "your technician account is approved" email only after the
 * approval transaction has actually committed, so a technician is never
 * emailed for an approval that then failed to save. Approval itself must
 * succeed even if mail delivery is temporarily unavailable: EmailService
 * already catches and logs delivery failures internally rather than
 * throwing, and this listener runs asynchronously after commit, so a mail
 * outage can never roll back or block the already-committed approval.
 */
@Component
@RequiredArgsConstructor
public class TechnicianApprovalEmailListener {
    private final EmailService emailService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendAfterCommit(TechnicianApprovalEmailEvent event) {
        emailService.sendTechnicianApprovalEmail(event.technicianId(), event.personalEmail(), event.technicianName());
    }
}
