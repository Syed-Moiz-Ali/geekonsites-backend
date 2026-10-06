package com.geekonsites.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

class ResendEmailClientTest {

    @Test
    void sendsRequestToResendWithBearerAuthAndJsonPayload() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResendEmailClient client = new ResendEmailClient(builder, "test-resend-key");

        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-resend-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "from": "GeekOnSites Support <support@geekonsites.com>",
                          "to": ["user@example.com"],
                          "subject": "Reset your GeekOnSites password",
                          "html": "<p>reset link</p>"
                        }
                        """))
                .andRespond(withSuccess("{\"id\":\"email_123\"}", MediaType.APPLICATION_JSON));

        boolean delivered = client.send(
                "GeekOnSites Support <support@geekonsites.com>",
                "user@example.com",
                "Reset your GeekOnSites password",
                "<p>reset link</p>"
        );

        assertTrue(delivered);
        server.verify();
    }

    @Test
    void returnsFalseAndDoesNotThrowWhenResendRejectsTheRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResendEmailClient client = new ResendEmailClient(builder, "test-resend-key");

        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withUnauthorizedRequest());

        boolean delivered = client.send(
                "GeekOnSites Support <support@geekonsites.com>",
                "user@example.com",
                "Reset your GeekOnSites password",
                "<p>reset link</p>"
        );

        assertFalse(delivered);
    }

    @Test
    void returnsFalseAndDoesNotThrowOnServerError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResendEmailClient client = new ResendEmailClient(builder, "test-resend-key");

        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        boolean delivered = client.send(
                "GeekOnSites Support <support@geekonsites.com>",
                "user@example.com",
                "Reset your GeekOnSites password",
                "<p>reset link</p>"
        );

        assertFalse(delivered);
    }

    @Test
    void skipsTheHttpCallEntirelyWhenApiKeyIsNotConfigured() {
        RestClient.Builder builder = RestClient.builder();
        // No expectations registered: if the client attempted an HTTP call
        // anyway, MockRestServiceServer would throw on the unexpected
        // request, proving the guard clause short-circuits before any I/O.
        MockRestServiceServer.bindTo(builder).build();
        ResendEmailClient client = new ResendEmailClient(builder, "");

        boolean delivered = client.send(
                "GeekOnSites Support <support@geekonsites.com>",
                "user@example.com",
                "Reset your GeekOnSites password",
                "<p>reset link</p>"
        );

        assertFalse(delivered);
    }
}
