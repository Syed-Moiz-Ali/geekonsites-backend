package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.BookingRequest;
import com.geekonsites.backend.enums.ServiceMode;
import org.springframework.stereotype.Service;

@Service
public class PricingService {

    public BookingRequest calculatePricing(BookingRequest request) {

        String country = normalizeCountry(request.getCountry());
        request.setCountry(country);
        request.setCurrency("UK".equals(country) ? "GBP" : "USD");

        double baseAmount = safe(request.getBaseAmount());
        double addonsAmount = safe(request.getAddonsAmount());
        double protectionAmount = safe(request.getProtectionAmount());

        double platformFee = getPlatformFee(request.getCurrency());

        double totalAmount =
                baseAmount +
                addonsAmount +
                protectionAmount +
                platformFee;

        double advanceAmount;
        double remainingAmount;
        double paidAmount;

        if (request.getServiceMode() == ServiceMode.ONSITE) {
            advanceAmount = round(totalAmount * 0.30);
            remainingAmount = round(totalAmount - advanceAmount);
            paidAmount = advanceAmount;
            request.setPaymentType("ADVANCE_PAYMENT");
            request.setPaymentStatus("PARTIALLY_PAID");
        } else {
            advanceAmount = 0;
            remainingAmount = 0;
            paidAmount = totalAmount;
            request.setPaymentType("FULL_PAYMENT");
            request.setPaymentStatus("PENDING");
        }

        request.setPlatformFee(platformFee);
        request.setTotalAmount(round(totalAmount));
        request.setAdvanceAmount(round(advanceAmount));
        request.setRemainingAmount(round(remainingAmount));
        request.setPaidAmount(round(paidAmount));

        return request;
    }

    private String normalizeCountry(String country) {
        String value = country == null ? "" : country.trim().toUpperCase().replace('_', ' ');
        if (value.equals("UK") || value.equals("GB") || value.equals("GBR") ||
                value.equals("GBP") || value.equals("UNITED KINGDOM") || value.equals("GREAT BRITAIN")) {
            return "UK";
        }
        return "US";
    }

    private double getPlatformFee(String currency) {
        if (currency == null) return 12.0;

        String value = currency.toUpperCase();

        if (value.equals("GBP") || value.equals("£")) {
            return 12.0;
        }

        return 12.0;
    }

    private double safe(Double value) {
        return value == null ? 0.0 : value;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
