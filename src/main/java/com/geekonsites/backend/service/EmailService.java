package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.ContactMessage;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.io.UnsupportedEncodingException;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class EmailService {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailService.class);
    private final JavaMailSender mailSender;
    private final ResendEmailClient resendEmailClient;

    @Value("${app.mail.from:}")
    private String fromAddress;

    @Value("${app.mail.from-name:GeekOnSites}")
    private String fromName;

    @Value("${app.mail.support-address:support@geekonsites.com}")
    private String supportAddress;

    // Render's free web service plan blocks outbound SMTP ports, so password
    // reset delivery uses the Resend HTTPS API rather than JavaMailSender.
    @Value("${resend.password-reset.from:GeekOnSites Support <support@geekonsites.com>}")
    private String passwordResetFrom;

    @Async
    public void sendPasswordResetEmail(String to, String resetLink) {
        boolean delivered = resendEmailClient.send(
                passwordResetFrom,
                to,
                "Reset your GeekOnSites password",
                passwordResetHtml(resetLink)
        );
        if (!delivered) {
            LOGGER.error("Password reset email delivery failed");
        }
    }

    @Async
    public void sendEmail(String to, String subject, String body) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            if (fromAddress != null && !fromAddress.isBlank()) helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(message);
        } catch (MessagingException | UnsupportedEncodingException | RuntimeException exception) {
            LOGGER.error("Email delivery failed", exception);
        }
    }

    public boolean sendTechnicianOnboardingEmail(
            String to,
            String technicianName,
            String companyEmail,
            String setupLink
    ) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            if (fromAddress != null && !fromAddress.isBlank()) helper.setFrom(fromAddress, fromName);
            helper.setReplyTo(supportAddress);
            helper.setTo(to);
            helper.setSubject("Your GeekOnSites Technician Application Has Been Approved");
            helper.setText(technicianOnboardingHtml(technicianName, companyEmail, setupLink), true);
            mailSender.send(message);
            return true;
        } catch (MessagingException | UnsupportedEncodingException | RuntimeException exception) {
            LOGGER.error("Technician onboarding email delivery failed", exception);
            return false;
        }
    }

    public void sendTechnicianApprovalEmail(Long technicianId, String to, String technicianName) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            if (fromAddress != null && !fromAddress.isBlank()) helper.setFrom(fromAddress, fromName);
            helper.setReplyTo(supportAddress);
            helper.setTo(to);
            helper.setSubject("Your GeekOnSites Technician Account Is Approved");
            helper.setText(technicianApprovalHtml(technicianId, technicianName, to), true);
            mailSender.send(message);
        } catch (MessagingException | UnsupportedEncodingException | RuntimeException exception) {
            LOGGER.error("Technician approval email delivery failed for technicianId={}", technicianId, exception);
        }
    }

    @Async
    public void sendContactMessageEmails(ContactMessage contact) {
        try {
            sendPlainTextMessage(
                    supportAddress,
                    "New GeekOnSites Customer Message",
                    supportNotificationBody(contact),
                    supportAddress
            );
        } catch (MessagingException | UnsupportedEncodingException | RuntimeException exception) {
            LOGGER.error("Contact support notification delivery failed for message {}", contact.getId(), exception);
        }

        try {
            sendPlainTextMessage(
                    contact.getEmail(),
                    "Thank you for contacting GeekOnSites",
                    customerConfirmationBody(contact),
                    supportAddress
            );
        } catch (MessagingException | UnsupportedEncodingException | RuntimeException exception) {
            LOGGER.error("Contact customer confirmation delivery failed for message {}", contact.getId(), exception);
        }
    }

    private void sendPlainTextMessage(String to, String subject, String body, String replyTo)
            throws MessagingException, UnsupportedEncodingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        if (fromAddress != null && !fromAddress.isBlank()) helper.setFrom(fromAddress, fromName);
        if (replyTo != null && !replyTo.isBlank()) helper.setReplyTo(replyTo);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(body, false);
        mailSender.send(message);
    }

    private String supportNotificationBody(ContactMessage contact) {
        return """
                New customer contact request received.

                Name: %s
                Email: %s
                Phone: %s
                Subject: %s

                Message:
                %s

                Submitted: %s
                """.formatted(
                contact.getFullName(),
                contact.getEmail(),
                contact.getPhone() == null || contact.getPhone().isBlank() ? "Not provided" : contact.getPhone(),
                contact.getSubject(),
                contact.getMessage(),
                contact.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
    }

    private String customerConfirmationBody(ContactMessage contact) {
        return """
                Hello %s,

                Thank you for reaching out to GeekOnSites.

                We have received your message successfully. Our support team will review your request and get back to you as soon as possible.

                If your request relates to an existing service booking, please keep your booking details available so our team can assist you efficiently.

                Thank you for choosing GeekOnSites.

                Best regards,
                GeekOnSites Support Team
                %s
                https://geekonsites.com
                """.formatted(contact.getFullName(), supportAddress);
    }

    private String passwordResetHtml(String resetLink) {
        return """
                <!doctype html><html><body style="margin:0;background:#edf2f5;font-family:Arial,sans-serif;color:#17324d">
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="padding:32px 16px"><tr><td align="center">
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:560px;background:#fff;border:1px solid #dbe3e8;border-radius:8px;overflow:hidden">
                <tr><td style="background:#092b4c;padding:22px 28px;color:#fff;font-size:22px;font-weight:700">Geek<span style="color:#31c2b8">On</span>Sites</td></tr>
                <tr><td style="padding:30px 28px"><p style="margin:0 0 8px;color:#31a99f;font-size:11px;font-weight:700;text-transform:uppercase">Secure account recovery</p>
                <h1 style="margin:0 0 16px;font-size:28px;line-height:1.15;color:#092b4c">Reset your password</h1>
                <p style="margin:0 0 24px;font-size:14px;line-height:1.7;color:#536779">We received a request to reset your GeekOnSites password. Use the secure button below to create a new password.</p>
                <a href="%s" style="display:inline-block;background:#092b4c;color:#fff;text-decoration:none;font-size:14px;font-weight:700;padding:13px 22px;border-radius:7px">Reset password</a>
                <p style="margin:24px 0 0;font-size:12px;line-height:1.6;color:#6b7c8a">This link expires in 30 minutes and can be used once. If you did not request this change, you can safely ignore this email.</p>
                </td></tr></table></td></tr></table></body></html>
                """.formatted(resetLink);
    }

    private String technicianApprovalHtml(Long technicianId, String name, String registeredEmail) {
        String safeName = (name == null || name.isBlank()) ? "there" : name.trim().split("\\s+")[0];
        String technicianIdRow = technicianId == null ? "" : """
                <p style="margin:0 0 6px;font-size:12px;font-weight:700;text-transform:uppercase;color:#6b7c8a">Technician ID</p>
                <p style="margin:0 0 24px;font-size:14px;font-weight:700;color:#092b4c">GOS-T-%d</p>
                """.formatted(technicianId);
        return """
                <!doctype html><html><body style="margin:0;background:#edf2f5;font-family:Arial,sans-serif;color:#17324d">
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="padding:32px 16px"><tr><td align="center">
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:560px;background:#fff;border:1px solid #dbe3e8;border-radius:8px;overflow:hidden">
                <tr><td style="background:#092b4c;padding:22px 28px;color:#fff;font-size:22px;font-weight:700">Geek<span style="color:#31c2b8">On</span>Sites</td></tr>
                <tr><td style="padding:30px 28px">
                <p style="margin:0 0 8px;color:#31a99f;font-size:11px;font-weight:700;text-transform:uppercase">Technician portal</p>
                <h1 style="margin:0 0 16px;font-size:24px;line-height:1.25;color:#092b4c">Your GeekOnSites Technician Account Is Approved</h1>
                <p style="margin:0 0 16px;font-size:14px;line-height:1.7;color:#425a6e">Hello %s,</p>
                <p style="margin:0 0 16px;font-size:14px;line-height:1.7;color:#425a6e">Your application to work with GeekOnSites has been approved.</p>
                <p style="margin:0 0 8px;font-size:14px;line-height:1.7;color:#425a6e">You can now sign in to the GeekOnSites Technician Portal using the email address and password you created during registration.</p>
                <p style="margin:0 0 6px;font-size:12px;font-weight:700;text-transform:uppercase;color:#6b7c8a">Registered email</p>
                <p style="margin:0 0 24px;font-size:16px;font-weight:700;color:#092b4c">%s</p>
                %s
                <a href="https://geekonsites.com/technician-login" style="display:inline-block;background:#092b4c;color:#fff;text-decoration:none;font-size:14px;font-weight:700;padding:13px 22px;border-radius:7px">Sign in to Technician Portal</a>
                <p style="margin:24px 0 0;font-size:12px;line-height:1.6;color:#6b7c8a">Security note: GeekOnSites will never ask for your password by email.</p>
                <p style="margin:18px 0 0;font-size:14px;line-height:1.7;color:#425a6e">Regards,<br>GeekOnSites Team</p>
                </td></tr></table></td></tr></table></body></html>
                """.formatted(safeName, registeredEmail, technicianIdRow);
    }

    private String technicianOnboardingHtml(String name, String companyEmail, String setupLink) {
        return """
                <!doctype html><html><body style="font-family:Arial,sans-serif;color:#17324d;background:#edf2f5;padding:24px">
                <div style="max-width:580px;margin:auto;background:#fff;border:1px solid #dbe3e8;border-radius:10px;overflow:hidden">
                <div style="background:#092b4c;color:#fff;padding:22px 28px;font-size:22px;font-weight:700">GeekOnSites</div>
                <div style="padding:30px 28px"><h1 style="color:#092b4c">Application approved</h1>
                <p>Hi %s,</p><p>Your GeekOnSites technician application has been approved.</p>
                <p>Your official GeekOnSites technician login email is:</p>
                <p style="font-size:18px;font-weight:700;color:#092b4c">%s</p>
                <p>To activate your technician account, securely create your password using the button below.</p>
                <a href="%s" style="display:inline-block;background:#092b4c;color:#fff;text-decoration:none;font-weight:700;padding:13px 22px;border-radius:7px">Set Your Password</a>
                <p style="font-size:12px;color:#6b7c8a">This link expires in 24 hours and can only be used once.</p>
                <p>After setting your password, sign in at <a href="https://geekonsites.com/technician-login">GeekOnSites Technician Login</a>.</p>
                <p>If you did not submit this application, contact %s.</p>
                <p>GeekOnSites<br>A service by ASI TECH INC</p></div></div></body></html>
                """.formatted(name, companyEmail, setupLink, supportAddress);
    }
}
