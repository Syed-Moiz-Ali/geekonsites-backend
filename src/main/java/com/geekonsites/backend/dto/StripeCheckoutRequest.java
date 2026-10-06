package com.geekonsites.backend.dto;

public class StripeCheckoutRequest {
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
