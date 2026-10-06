package com.geekonsites.backend.dto;

public class StripeCheckoutResponse {
    private String checkoutUrl;
    private String sessionId;

    public StripeCheckoutResponse(String checkoutUrl, String sessionId) {
        this.checkoutUrl = checkoutUrl;
        this.sessionId = sessionId;
    }

    public String getCheckoutUrl() {
        return checkoutUrl;
    }

    public String getSessionId() {
        return sessionId;
    }
}