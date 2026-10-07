package com.geekonsites.backend.dto;

import jakarta.validation.constraints.NotNull;

public class StripeCheckoutRequest {
    @NotNull(message = "bookingId is required")
    private Long bookingId;
    private String paymentType;
    private Boolean ukEarlyServiceConsent;

    public Long getBookingId() {
        return bookingId;
    }

    public void setBookingId(Long bookingId) {
        this.bookingId = bookingId;
    }

    public String getPaymentType() {
        return paymentType;
    }

    public void setPaymentType(String paymentType) {
        this.paymentType = paymentType;
    }

    public Boolean getUkEarlyServiceConsent() {
        return ukEarlyServiceConsent;
    }

    public void setUkEarlyServiceConsent(Boolean ukEarlyServiceConsent) {
        this.ukEarlyServiceConsent = ukEarlyServiceConsent;
    }
}
