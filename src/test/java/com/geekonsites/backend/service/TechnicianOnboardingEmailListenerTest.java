package com.geekonsites.backend.service;

import com.geekonsites.backend.enums.TechnicianOnboardingStatus;
import com.geekonsites.backend.repository.TechnicianRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TechnicianOnboardingEmailListenerTest {
    @Test
    void listenerRunsAfterCommitWithAnIndependentStatusUpdateTransaction() throws Exception {
        Method listener = TechnicianOnboardingEmailListener.class
                .getMethod("sendAfterCommit", TechnicianOnboardingEmailEvent.class);

        assertEquals(TransactionPhase.AFTER_COMMIT,
                listener.getAnnotation(TransactionalEventListener.class).phase());
        assertEquals(Propagation.REQUIRES_NEW,
                listener.getAnnotation(Transactional.class).propagation());
    }

    @Test
    void emailFailureRecordsFailureWithoutChangingApproval() {
        EmailService email = mock(EmailService.class);
        TechnicianRepository technicians = mock(TechnicianRepository.class);
        when(email.sendTechnicianOnboardingEmail(anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        TechnicianOnboardingEmailListener listener = new TechnicianOnboardingEmailListener(email, technicians);

        listener.sendAfterCommit(new TechnicianOnboardingEmailEvent(1L, "personal@example.com", "Rahul", "rahul@gos.com", "raw"));

        verify(technicians).updateOnboardingDeliveryStatus(1L, TechnicianOnboardingStatus.EMAIL_FAILED, null);
        verify(technicians, never()).save(any());
    }
}
