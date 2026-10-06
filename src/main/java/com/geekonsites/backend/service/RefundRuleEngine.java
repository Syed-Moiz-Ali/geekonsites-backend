package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;

@Service
public class RefundRuleEngine {

    public RefundAssessment assess(Booking booking, BigDecimal alreadyRefunded) {
        BigDecimal paid = money(booking.getPaidAmount());
        BigDecimal remainingCaptured = paid.subtract(alreadyRefunded == null ? BigDecimal.ZERO : alreadyRefunded)
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        boolean uk = isUk(booking.getCountry());
        boolean started = booking.getServiceStartedAt() != null || booking.getRemoteSessionStartedAt() != null;
        boolean fullyPerformed = booking.getServiceCompletedAt() != null;
        boolean withinUkPeriod = uk && withinUkCancellationPeriod(booking);

        String context;
        if (!uk) {
            context = started
                    ? "USA: service has started; admin must deduct only documented service, approved parts, dispatch, or travel actually supplied, while preserving applicable consumer remedies."
                    : "USA: service has not started; unprovided service is generally refundable, subject to documented eligible costs and applicable consumer law.";
        } else if (withinUkPeriod && started && Boolean.TRUE.equals(booking.getUkEarlyServiceConsent())) {
            context = fullyPerformed
                    ? "UK: service was fully performed during the 14-day period with recorded early-service consent; statutory cancellation and defective-service rights must still be reviewed."
                    : "UK: service began during the 14-day period with recorded consent; only a lawful proportionate amount for service actually supplied may be retained. Consumer Rights Act remedies remain available.";
        } else if (withinUkPeriod) {
            context = "UK: request falls within the statutory 14-day period. No blanket deduction is permitted; early-service consent and Consumer Rights Act remedies must be reviewed.";
        } else {
            context = "UK: outside the initial 14-day period; assess unprovided service, documented costs, and Consumer Rights Act remedies without blanket non-refundable terms.";
        }

        // The platform fee remains inside the ceiling. It may be retained only after an
        // admin documents that the associated function was supplied and retention is lawful.
        return new RefundAssessment(remainingCaptured, context);
    }

    private boolean withinUkCancellationPeriod(Booking booking) {
        if (booking.getCreatedAt() == null) return true;
        LocalDate reference = booking.getBookingDate() == null
                ? LocalDate.now()
                : booking.getBookingDate();
        return !reference.isAfter(booking.getCreatedAt().toLocalDate().plusDays(14));
    }

    private boolean isUk(String country) {
        if (country == null) return false;
        String value = country.trim().toUpperCase(Locale.ROOT);
        return value.equals("UK") || value.equals("GB") || value.equals("UNITED KINGDOM") || value.equals("GREAT BRITAIN");
    }

    private BigDecimal money(Double amount) {
        return BigDecimal.valueOf(amount == null ? 0 : amount).setScale(2, RoundingMode.HALF_UP);
    }

    public record RefundAssessment(BigDecimal maximumRefundableAmount, String ruleContext) {}
}
