package com.geekonsites.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * Minimal HTTPS client for the Resend transactional email API
 * (https://resend.com/docs/api-reference/emails/send-email).
 *
 * Render's free web service plan blocks outbound SMTP ports (25/465/587), so
 * SMTP/JavaMailSender cannot deliver mail from production. This client sends
 * mail over HTTPS instead and is currently used only for password-reset
 * delivery; other email flows continue to use JavaMailSender.
 */
@Component
public class ResendEmailClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResendEmailClient.class);
    private static final String RESEND_EMAILS_ENDPOINT = "https://api.resend.com/emails";

    private final RestClient restClient;
    private final String apiKey;

    public ResendEmailClient(RestClient.Builder restClientBuilder, @Value("${resend.api-key:}") String apiKey) {
        this.restClient = restClientBuilder.build();
        this.apiKey = apiKey;
    }

    /**
     * Sends a single HTML email through the Resend API.
     * <p>
     * Never throws: delivery failures are logged (without the API key, the
     * recipient, or the email contents) and reported back as {@code false}
     * so callers can decide how to handle a failed send.
     */
    public boolean send(String from, String to, String subject, String html) {
        if (apiKey == null || apiKey.isBlank()) {
            LOGGER.error("Resend email delivery skipped: RESEND_API_KEY is not configured");
            return false;
        }
        try {
            restClient.post()
                    .uri(RESEND_EMAILS_ENDPOINT)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ResendEmailRequest(from, List.of(to), subject, html))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException exception) {
            LOGGER.error("Resend email delivery failed with HTTP status {}", exception.getStatusCode().value());
            return false;
        } catch (RestClientException exception) {
            LOGGER.error("Resend email delivery failed", exception);
            return false;
        }
    }

    private record ResendEmailRequest(String from, List<String> to, String subject, String html) {
    }
}
