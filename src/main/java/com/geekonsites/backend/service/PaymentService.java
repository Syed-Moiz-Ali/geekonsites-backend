package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.StripeCheckoutResponse;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.RefundRequest;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.RefundStatus;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.RefundRequestRepository;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private final BookingRepository bookingRepository;
    private final InvoiceService invoiceService;
    private final RemoteSessionProvisioningService remoteSessionProvisioningService;
    private final UkEarlyServiceConsentService ukEarlyServiceConsentService;
    private final RefundRequestRepository refundRequestRepository;
    private final NotificationService notificationService;

    @Value("${stripe.secret.key}")
    private String stripeSecretKey;

    @Value("${stripe.success.url}")
    private String successUrl;

    @Value("${stripe.cancel.url}")
    private String cancelUrl;

    @Value("${stripe.webhook.secret}")
    private String webhookSecret;

    public PaymentService(
            BookingRepository bookingRepository,
            InvoiceService invoiceService,
            RemoteSessionProvisioningService remoteSessionProvisioningService,
            UkEarlyServiceConsentService ukEarlyServiceConsentService,
            RefundRequestRepository refundRequestRepository,
            NotificationService notificationService
    ) {
        this.bookingRepository = bookingRepository;
        this.invoiceService = invoiceService;
        this.remoteSessionProvisioningService = remoteSessionProvisioningService;
        this.ukEarlyServiceConsentService = ukEarlyServiceConsentService;
        this.refundRequestRepository = refundRequestRepository;
        this.notificationService = notificationService;
    }

    public StripeCheckoutResponse createCheckoutSession(
            Long bookingId,
            String paymentType,
            Long customerId,
            Boolean ukEarlyServiceConsent
    ) {
        try {
            Booking booking = bookingRepository.findById(bookingId)
                    .orElseThrow(() -> new RuntimeException("Booking not found"));

            if (!customerId.equals(booking.getCustomerId())) {
                throw new RuntimeException("You cannot pay for another customer's booking");
            }

            String normalizedPaymentType = normalizePaymentType(paymentType);
            validatePaymentStage(booking, normalizedPaymentType);
            ukEarlyServiceConsentService.recordForInitialPayment(
                    booking,
                    customerId,
                    normalizedPaymentType,
                    ukEarlyServiceConsent
            );

            Stripe.apiKey = stripeSecretKey;

            double amountToPay;

            amountToPay = expectedPaymentAmount(booking, normalizedPaymentType);

            if (amountToPay <= 0) {
                throw new RuntimeException("Invalid payment amount");
            }

            String currency = booking.getCurrency() != null
                    ? booking.getCurrency().toLowerCase()
                    : "usd";

            long amountInSmallestUnit = Math.round(amountToPay * 100);

            SessionCreateParams params =
                    SessionCreateParams.builder()
                            .setMode(SessionCreateParams.Mode.PAYMENT)
                            .setSuccessUrl(successUrl + "?bookingId=" + bookingId + "&session_id={CHECKOUT_SESSION_ID}")
                            .setCancelUrl(cancelUrl + "?bookingId=" + bookingId)
                            .addLineItem(
                                    SessionCreateParams.LineItem.builder()
                                            .setQuantity(1L)
                                            .setPriceData(
                                                    SessionCreateParams.LineItem.PriceData.builder()
                                                            .setCurrency(currency)
                                                            .setUnitAmount(amountInSmallestUnit)
                                                            .setProductData(
                                                                    SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                                            .setName("GeekOnSites Booking GOS-" + bookingId)
                                                                            .setDescription(booking.getServiceType())
                                                                            .build()
                                                            )
                                                            .build()
                                            )
                                            .build()
                            )
                            .putMetadata("bookingId", String.valueOf(bookingId))
                            .putMetadata("paymentType", normalizedPaymentType)
                            .build();

            Session session = Session.create(params);

            return new StripeCheckoutResponse(session.getUrl(), session.getId());

        } catch (Exception e) {
            throw new RuntimeException("Failed to create Stripe checkout session: " + e.getMessage());
        }
    }

    public void handleWebhook(String payload, String sigHeader) {
        try {
            Event event = Webhook.constructEvent(payload, sigHeader, webhookSecret);

            if ("checkout.session.completed".equals(event.getType())) {
                Session session = (Session) event.getDataObjectDeserializer()
                        .getObject()
                        .orElseThrow(() -> new RuntimeException("Unable to deserialize Stripe session"));

                applyCompletedCheckoutSession(session, null);
            }

            if ("refund.updated".equals(event.getType())) {
                Refund stripeRefund = (Refund) event.getDataObjectDeserializer()
                        .getObject()
                        .orElseThrow(() -> new RuntimeException("Unable to deserialize Stripe refund"));
                refundRequestRepository.findByStripeRefundId(stripeRefund.getId()).ifPresent(refund -> {
                    if ("succeeded".equalsIgnoreCase(stripeRefund.getStatus())) {
                        refund.setRefundStatus(refund.getApprovedRefundAmount() != null
                                && refund.getApprovedRefundAmount().compareTo(refund.getOriginalPaymentAmount()) < 0
                                ? RefundStatus.PARTIALLY_REFUNDED : RefundStatus.REFUNDED);
                    } else if ("failed".equalsIgnoreCase(stripeRefund.getStatus())
                            || "canceled".equalsIgnoreCase(stripeRefund.getStatus())) {
                        refund.setRefundStatus(RefundStatus.FAILED);
                        refund.setFailureReason("Stripe reported refund status: " + stripeRefund.getStatus());
                    }
                    refund.setProcessedAt(java.time.LocalDateTime.now());
                    refundRequestRepository.save(refund);
                });
            }

        } catch (SignatureVerificationException e) {
            throw new RuntimeException("Invalid Stripe webhook signature");
        } catch (Exception e) {
            throw new RuntimeException("Stripe webhook failed: " + e.getMessage());
        }
    }

    /**
     * Used by the success page as a reliable fallback when a Stripe webhook is
     * delayed or not yet configured. Stripe is queried server-side; the browser
     * cannot mark a booking as paid on its own.
     */
    public Booking confirmCheckoutSession(String sessionId, Long customerId) {
        try {
            Stripe.apiKey = stripeSecretKey;
            Session session = Session.retrieve(sessionId);

            if (!"paid".equalsIgnoreCase(session.getPaymentStatus())) {
                throw new RuntimeException("Stripe has not confirmed this payment yet");
            }

            return applyCompletedCheckoutSession(session, customerId);
        } catch (Exception e) {
            throw new RuntimeException("Unable to confirm Stripe payment: " + e.getMessage());
        }
    }

    Booking applyCompletedCheckoutSession(Session session, Long expectedCustomerId) {
        String bookingIdText = session.getMetadata().get("bookingId");
        String paymentType = session.getMetadata().get("paymentType");

        if (bookingIdText == null) {
            throw new RuntimeException("Booking ID missing in Stripe metadata");
        }

        Long bookingId = Long.parseLong(bookingIdText);
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        if (expectedCustomerId != null && !expectedCustomerId.equals(booking.getCustomerId())) {
            throw new RuntimeException("You cannot confirm another customer's payment");
        }

        if (!"paid".equalsIgnoreCase(session.getPaymentStatus())) {
            throw new RuntimeException("Stripe has not confirmed this payment");
        }

        // Idempotent: Stripe can retry webhooks and customers can refresh the success page.
        if (session.getId().equals(booking.getPaymentTransactionId())) {
            notificationService.createPaymentSuccessNotification(booking, paymentType, session.getId());
            return finalizePaidBooking(booking);
        }

        String normalizedPaymentType = normalizePaymentType(paymentType);
        validatePaymentStage(booking, normalizedPaymentType);
        verifyStripeAmountAndCurrency(session, booking, normalizedPaymentType);

        booking.setPaymentTransactionId(session.getId());
        booking.setPaymentMethod("STRIPE");
        booking.setPaymentType(normalizedPaymentType);

        if ("ADVANCE".equals(normalizedPaymentType)) {
            booking.setPaymentStatus("PARTIALLY_PAID");
            booking.setBookingStatus(BookingStatus.ASSIGNMENT_PENDING);
            booking.setPaidAmount(booking.getAdvanceAmount());
        } else if ("REMAINING".equals(normalizedPaymentType)) {
            booking.setPaymentStatus("PAID");
            booking.setRemainingAmount(0.0);
            booking.setPaidAmount(booking.getTotalAmount());
            booking.setBookingStatus(BookingStatus.SERVICE_COMPLETED);
        } else {
            booking.setPaymentStatus("PAID");
            booking.setBookingStatus(BookingStatus.PAYMENT_COMPLETED);
            booking.setPaidAmount(booking.getTotalAmount());
        }

        Booking savedBooking = bookingRepository.save(booking);
        notificationService.createPaymentSuccessNotification(savedBooking, normalizedPaymentType, session.getId());
        return finalizePaidBooking(savedBooking);
    }

    private String normalizePaymentType(String paymentType) {
        String value = paymentType == null ? "FULL" : paymentType.trim().toUpperCase();
        if (!value.equals("FULL") && !value.equals("ADVANCE") && !value.equals("REMAINING")) {
            throw new RuntimeException("Unsupported payment type");
        }
        return value;
    }

    private void validatePaymentStage(Booking booking, String paymentType) {
        boolean onsite = booking.getServiceMode() != null && "ONSITE".equals(booking.getServiceMode().name());
        if (!onsite && !"FULL".equals(paymentType)) {
            throw new RuntimeException("Remote services require full payment");
        }
        if (onsite && "FULL".equals(paymentType)) {
            throw new RuntimeException("On-site services use advance payment before the visit");
        }
        if ("ADVANCE".equals(paymentType) && !"PENDING".equalsIgnoreCase(booking.getPaymentStatus())) {
            throw new RuntimeException("The advance payment has already been processed");
        }
        if ("REMAINING".equals(paymentType)
                && !"BALANCE_PENDING".equalsIgnoreCase(booking.getPaymentStatus())) {
            throw new RuntimeException("The remaining balance is not due yet");
        }
        if ("FULL".equals(paymentType) && !"PENDING".equalsIgnoreCase(booking.getPaymentStatus())) {
            throw new RuntimeException("This booking has already been paid");
        }
    }

    private double expectedPaymentAmount(Booking booking, String paymentType) {
        if ("ADVANCE".equals(paymentType)) return booking.getAdvanceAmount();
        if ("REMAINING".equals(paymentType)) return booking.getRemainingAmount();
        return booking.getTotalAmount();
    }

    private void verifyStripeAmountAndCurrency(
            Session session,
            Booking booking,
            String paymentType
    ) {
        long expectedAmount = Math.round(expectedPaymentAmount(booking, paymentType) * 100);
        if (session.getAmountTotal() == null || session.getAmountTotal() != expectedAmount) {
            throw new RuntimeException("Stripe payment amount does not match the booking");
        }
        String expectedCurrency = booking.getCurrency() == null
                ? "usd"
                : booking.getCurrency().toLowerCase();
        if (session.getCurrency() == null || !expectedCurrency.equalsIgnoreCase(session.getCurrency())) {
            throw new RuntimeException("Stripe payment currency does not match the booking");
        }
    }

    private Booking finalizePaidBooking(Booking booking) {
        if ("PARTIALLY_PAID".equalsIgnoreCase(booking.getPaymentStatus())) {
            invoiceService.generateInvoiceFromBooking(booking.getId());
            return bookingRepository.findById(booking.getId()).orElse(booking);
        }
        if (!"PAID".equalsIgnoreCase(booking.getPaymentStatus())) return booking;
        invoiceService.generateInvoiceFromBooking(booking.getId());
        return remoteSessionProvisioningService.provisionAfterPayment(booking.getId());
    }
}
