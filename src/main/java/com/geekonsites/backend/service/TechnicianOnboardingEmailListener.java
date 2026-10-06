package com.geekonsites.backend.service;

import com.geekonsites.backend.enums.TechnicianOnboardingStatus;
import com.geekonsites.backend.repository.TechnicianRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class TechnicianOnboardingEmailListener {
    private final EmailService emailService;
    private final TechnicianRepository technicianRepository;

    @Value("${app.technician-onboarding.setup-url:https://geekonsites.com/technician/set-password}")
    private String setupUrl;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendAfterCommit(TechnicianOnboardingEmailEvent event) {
        boolean sent = emailService.sendTechnicianOnboardingEmail(
                event.personalEmail(), event.technicianName(), event.companyEmail(),
                setupUrl + "?token=" + event.rawToken()
        );
        technicianRepository.updateOnboardingDeliveryStatus(
                event.technicianId(),
                sent ? TechnicianOnboardingStatus.EMAIL_SENT : TechnicianOnboardingStatus.EMAIL_FAILED,
                sent ? Instant.now() : null
        );
    }
}
