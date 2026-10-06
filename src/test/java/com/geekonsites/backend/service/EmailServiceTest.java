package com.geekonsites.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmailServiceTest {

    private JavaMailSender mailSender;
    private ResendEmailClient resendEmailClient;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        resendEmailClient = mock(ResendEmailClient.class);
        emailService = new EmailService(mailSender, resendEmailClient);
        ReflectionTestUtils.setField(emailService, "passwordResetFrom", "GeekOnSites Support <support@geekonsites.com>");
    }

    @Test
    void passwordResetEmailIsDeliveredThroughResendNotSmtp() {
        when(resendEmailClient.send(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        emailService.sendPasswordResetEmail("user@example.com", "https://geekonsites.com/reset-password?token=abc123");

        ArgumentCaptor<String> from = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(resendEmailClient).send(from.capture(), to.capture(), subject.capture(), html.capture());

        assertEquals("GeekOnSites Support <support@geekonsites.com>", from.getValue());
        assertEquals("user@example.com", to.getValue());
        assertEquals("Reset your GeekOnSites password", subject.getValue());
        assertTrue(html.getValue().contains("https://geekonsites.com/reset-password?token=abc123"));

        // Password-reset delivery must no longer rely on SMTP/JavaMailSender.
        verifyNoInteractions(mailSender);
    }

    @Test
    void resendFailureIsLoggedWithoutThrowing() {
        when(resendEmailClient.send(anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        emailService.sendPasswordResetEmail("user@example.com", "https://geekonsites.com/reset-password?token=abc123");

        verify(resendEmailClient).send(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(mailSender);
    }
}
