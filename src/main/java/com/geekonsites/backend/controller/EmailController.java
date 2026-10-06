package com.geekonsites.backend.controller;

import com.geekonsites.backend.service.EmailService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

//@RestController
@RequestMapping("/api/email")
public class EmailController {

    private final EmailService emailService;

    public EmailController(
            EmailService emailService
    ) {
        this.emailService = emailService;
    }

    @PostMapping("/test")
    public ResponseEntity<String> sendTestEmail() {

        emailService.sendEmail(
                "tejaswireddi2003@gmail.com",
                "GeekOnSite Test Email",
                "Congratulations! Email service is working."
        );

        return ResponseEntity.ok(
                "Email Sent Successfully"
        );
    }
}
