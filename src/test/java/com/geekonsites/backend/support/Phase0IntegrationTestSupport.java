package com.geekonsites.backend.support;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.enums.TechnicianOnboardingStatus;
import com.geekonsites.backend.jwt.JwtService;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.InvoiceRepository;
import com.geekonsites.backend.repository.NotificationRepository;
import com.geekonsites.backend.repository.PaymentRefundRepository;
import com.geekonsites.backend.repository.PaymentTransactionRepository;
import com.geekonsites.backend.repository.RatingRepository;
import com.geekonsites.backend.repository.RefundRequestRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * PHASE 0 regression-safety harness.
 *
 * <p>This is deliberately test-only infrastructure. It introduces no production
 * behaviour and no domain redesign. It only gives the Phase 0 regression suites a
 * single deterministic Spring context (in-memory H2, Firebase/Google disabled) plus
 * small factories so the "expected future behaviour" contract can be expressed
 * without duplicating setup in every class.
 *
 * <p>Every Phase 0 integration suite extends this class. Because all subclasses then
 * share identical configuration, Spring reuses one cached context. Each test starts
 * from an empty database via {@link #resetDatabase()}.
 */
@SpringBootTest(properties = {
        "spring.main.lazy-initialization=false",
        "spring.datasource.url=jdbc:h2:mem:phase0;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.jwt.secret=phase0-regression-secret-key-with-at-least-32-bytes",
        "firebase.enabled=false",
        "google.calendar.enabled=false",
        "spring.mail.host=localhost",
        "spring.mail.port=2525"
})
@AutoConfigureMockMvc
public abstract class Phase0IntegrationTestSupport {

    @Autowired protected MockMvc mvc;
    @Autowired protected UserRepository users;
    @Autowired protected TechnicianRepository technicians;
    @Autowired protected BookingRepository bookings;
    @Autowired protected InvoiceRepository invoices;
    @Autowired protected RatingRepository ratings;
    @Autowired protected NotificationRepository notifications;
    @Autowired protected PaymentTransactionRepository paymentTransactions;
    @Autowired protected PaymentRefundRepository paymentRefunds;
    @Autowired protected RefundRequestRepository refundRequests;
    @Autowired protected JwtService jwt;
    @Autowired protected PasswordEncoder passwordEncoder;

    @BeforeEach
    void resetDatabase() {
        // FK-safe order for the entities these tests touch. None of the Phase 0
        // test tables declare hard FKs to each other, but the order is kept
        // conservative so future additions do not start failing on cleanup.
        notifications.deleteAll();
        ratings.deleteAll();
        invoices.deleteAll();
        paymentRefunds.deleteAll();
        paymentTransactions.deleteAll();
        refundRequests.deleteAll();
        bookings.deleteAll();
        technicians.deleteAll();
        users.deleteAll();
    }

    /**
     * Performs a request and returns the HTTP status, treating an unhandled
     * controller exception (surfaced by MockMvc as a thrown exception) as 500.
     * Phase 0 regression tests use this to assert on status without MockMvc
     * aborting the test when the current code throws instead of returning 4xx.
     */
    protected int statusOf(MockHttpServletRequestBuilder request) {
        try {
            return mvc.perform(request).andReturn().getResponse().getStatus();
        } catch (Exception exception) {
            return 500;
        }
    }

    protected User saveUser(Role role, String email, String country) {
        User user = new User();
        user.setFullName(role.name() + " " + email);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("Password123!"));
        user.setPhone("+15550000000");
        user.setCountry(country);
        user.setRole(role);
        return users.save(user);
    }

    protected String bearer(User user) {
        return "Bearer " + jwt.generateToken(user);
    }

    /**
     * Creates a technician profile AND its login user. Technician login resolves via
     * {@code technicians.personal_email}, so both must share the same email.
     */
    protected Technician saveTechnician(String email, String verificationStatus,
                                        String availabilityStatus, String serviceMode) {
        Technician technician = new Technician();
        technician.setName("Tech " + email);
        technician.setEmail(email);
        technician.setPersonalEmail(email);
        technician.setVerificationStatus(verificationStatus);
        technician.setAvailabilityStatus(availabilityStatus);
        technician.setServiceMode(serviceMode);
        technician.setOnboardingStatus(TechnicianOnboardingStatus.PASSWORD_SET);
        technician.setRating(0.0);
        technician = technicians.save(technician);
        saveUser(Role.TECHNICIAN, email, "US");
        return technician;
    }

    protected Booking saveBooking(Long customerId, ServiceMode mode, String paymentStatus,
                                  BookingStatus bookingStatus) {
        Booking booking = new Booking();
        booking.setCustomerId(customerId);
        booking.setCustomerName("Regression Customer");
        booking.setCustomerEmail("regression-customer@example.com");
        booking.setCustomerPhone("+15550000000");
        booking.setServiceType("PC Health Check & Diagnosis");
        booking.setServiceMode(mode);
        booking.setCountry("US");
        booking.setCurrency("USD");
        booking.setBaseAmount(29.0);
        booking.setTotalAmount(41.0);
        booking.setAddonsAmount(0.0);
        booking.setProtectionAmount(0.0);
        booking.setPlatformFee(12.0);
        booking.setAdvanceAmount(0.0);
        booking.setRemainingAmount(0.0);
        booking.setPaidAmount("PAID".equalsIgnoreCase(paymentStatus) ? 41.0 : 0.0);
        // PHASE 8 — exact minor-unit authority for the seeded booking.
        booking.setBaseAmountMinor(2900L);
        booking.setAddonsAmountMinor(0L);
        booking.setProtectionAmountMinor(0L);
        booking.setPlatformFeeMinor(1200L);
        booking.setTotalAmountMinor(4100L);
        booking.setAdvanceAmountMinor(0L);
        booking.setRemainingAmountMinor(0L);
        booking.setPaidAmountMinor("PAID".equalsIgnoreCase(paymentStatus) ? 4100L : 0L);
        booking.setPaymentStatus(paymentStatus);
        booking.setPaymentType(ServiceMode.ONSITE == mode ? "ADVANCE_PAYMENT" : "FULL_PAYMENT");
        booking.setInvoiceGenerated(false);
        booking.setBookingClosed(false);
        booking.setRemoteSessionRequired(false);
        booking.setBookingStatus(bookingStatus);
        return bookings.save(booking);
    }

    protected Booking saveBookingForCustomer(Long customerId, Long technicianId, ServiceMode mode,
                                             String paymentStatus, BookingStatus bookingStatus) {
        Booking booking = saveBooking(customerId, mode, paymentStatus, bookingStatus);
        booking.setTechnicianId(technicianId);
        booking.setTechnicianName("Tech");
        return bookings.save(booking);
    }
}
