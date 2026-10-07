package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.BookingRequest;
import com.geekonsites.backend.dto.CustomerLocationRequest;
import com.geekonsites.backend.dto.TechnicianLocationRequest;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.NotificationType;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;


import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PHASE 2/4 — booking application/orchestration service.
 *
 * <p>This service performs authorization, entity lookup, technician eligibility and
 * side effects. Every booking lifecycle change is delegated to {@link BookingStateMachine};
 * this class never calls {@code booking.setBookingStatus(...)} itself.
 *
 * <p>PHASE 4: all public writes are {@code @Transactional}. Booking-row pessimistic locks
 * are taken for lifecycle/financial critical sections; notifications and Google Calendar
 * attendee sync run AFTER COMMIT via {@link BookingAssignedEvent}. Global lock order:
 * Booking → PaymentTransaction → PaymentRefund → RefundRequest → Technician(sorted by id).
 */
@Service
@Transactional
public class BookingService {

    private final BookingRepository bookingRepository;
    private final TechnicianRepository technicianRepository;
    private final TrustedPricingService pricingService;
    private final NotificationService notificationService;
    private final UkEarlyServiceConsentService ukEarlyServiceConsentService;
    private final RemoteSessionProvisioningService remoteSessionProvisioningService;
    private final BookingStateMachine stateMachine;
    private final ApplicationEventPublisher eventPublisher;
    private final InvoiceService invoiceService;

    public BookingService(
            BookingRepository bookingRepository,
            TechnicianRepository technicianRepository,
            TrustedPricingService pricingService,
            NotificationService notificationService,
            UkEarlyServiceConsentService ukEarlyServiceConsentService,
            RemoteSessionProvisioningService remoteSessionProvisioningService,
            BookingStateMachine stateMachine,
            ApplicationEventPublisher eventPublisher,
            InvoiceService invoiceService
    ) {
        this.bookingRepository = bookingRepository;
        this.technicianRepository = technicianRepository;
        this.pricingService = pricingService;
        this.notificationService = notificationService;
        this.ukEarlyServiceConsentService = ukEarlyServiceConsentService;
        this.remoteSessionProvisioningService = remoteSessionProvisioningService;
        this.stateMachine = stateMachine;
        this.eventPublisher = eventPublisher;
        this.invoiceService = invoiceService;
    }

    public Booking createBooking(BookingRequest request) {

        // PHASE 6: resolve service + price server-side from the DB catalog. Client-supplied
        // price/currency/mode fields are never trusted; service mode comes from the catalog.
        PricingQuote quote = pricingService.calculatePricing(request);

        Booking booking = new Booking();

        booking.setCustomerId(request.getCustomerId());
        booking.setCustomerName(request.getCustomerName());
        booking.setCustomerEmail(request.getCustomerEmail());
        booking.setCustomerPhone(request.getCustomerPhone());

        booking.setServiceId(quote.serviceId());
        booking.setServiceCodeSnapshot(quote.serviceCode());
        booking.setServiceNameSnapshot(quote.serviceName());
        booking.setServiceModeSnapshot(quote.serviceMode().name());
        booking.setServiceType(quote.serviceName());
        booking.setServiceMode(quote.serviceMode());
        booking.setIssueDescription(request.getIssueDescription());

        booking.setAddress(request.getAddress());
        booking.setCity(request.getCity());
        booking.setState(request.getState());
        booking.setPostalCode(request.getPostalCode());
        booking.setCountry(CountrySupport.normalize(request.getCountry()));
        booking.setCustomerLatitude(request.getCustomerLatitude());
        booking.setCustomerLongitude(request.getCustomerLongitude());

        if (request.getBookingDate() != null && !request.getBookingDate().isBlank()) {
            try {
                booking.setBookingDate(LocalDate.parse(request.getBookingDate()));
            } catch (java.time.format.DateTimeParseException error) {
                // PHASE 7: a format-valid but impossible date yields 400, never 500.
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "bookingDate must be a valid date in YYYY-MM-DD format");
            }
        }

        booking.setTimeSlot(request.getTimeSlot());
        booking.setCurrency(quote.currency().name());

        // PHASE 8 — exact minor units are authoritative; the Double fields are
        // deprecated mirrors derived from them for backward-compatible responses.
        booking.setBaseAmountMinor(quote.baseAmountMinor());
        booking.setAddonsAmountMinor(quote.addonAmountMinor());
        booking.setProtectionAmountMinor(quote.protectionAmountMinor());
        booking.setPlatformFeeMinor(quote.platformFeeMinor());
        booking.setTotalAmountMinor(quote.totalAmountMinor());
        booking.setAdvanceAmountMinor(quote.advanceAmountMinor());
        booking.setRemainingAmountMinor(quote.remainingAmountMinor());
        booking.setPaidAmountMinor(0L);

        booking.setBaseAmount(PaymentMoney.toMajor(quote.baseAmountMinor()));
        booking.setAddonsAmount(PaymentMoney.toMajor(quote.addonAmountMinor()));
        booking.setProtectionAmount(PaymentMoney.toMajor(quote.protectionAmountMinor()));
        booking.setPlatformFee(PaymentMoney.toMajor(quote.platformFeeMinor()));
        booking.setTotalAmount(PaymentMoney.toMajor(quote.totalAmountMinor()));

        booking.setAdvanceAmount(PaymentMoney.toMajor(quote.advanceAmountMinor()));
        booking.setRemainingAmount(PaymentMoney.toMajor(quote.remainingAmountMinor()));
        booking.setPaidAmount(0.0);

        booking.setPaymentType(quote.paymentType());
        booking.setPaymentStatus("PENDING");
        booking.setPaymentMethod(request.getPaymentMethod());
        booking.setPaymentTransactionId(null);

        booking.setSelectedAddons(quote.selectedAddons());
        booking.setProtectionPlan(request.getProtectionPlan());

        booking.setRemoteSessionRequired(
                request.getRemoteSessionRequired() != null
                        ? request.getRemoteSessionRequired()
                        : false
        );

        booking.setRemoteSessionLink(null);
        booking.setInvoiceGenerated(false);
        booking.setBookingClosed(false);
        // bookingStatus is initialised to PENDING by Booking@PrePersist; it is not a
        // business transition, so it is not routed through the state machine.

        Booking savedBooking = bookingRepository.save(booking);

        notificationService.createNotification(
        savedBooking.getCustomerId(),
        NotificationType.BOOKING_CREATED,
        "Booking Created",
        "Your " + savedBooking.getServiceType() + " booking has been created successfully.",
        NotificationType.BOOKING_CREATED + ":" + savedBooking.getId()
);

        return savedBooking;
    }

    /** PHASE 9 — bounded legacy list (max 100) plus a true paginated variant below. */
    @Transactional(readOnly = true)
    public List<Booking> getAllBookings() {
        return bookingRepository.findAllByOrderByCreatedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 100,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")))
                .getContent();
    }

    @Transactional(readOnly = true)
    public com.geekonsites.backend.dto.PageResponse<Booking> getAllBookings(org.springframework.data.domain.Pageable pageable) {
        return com.geekonsites.backend.dto.PageResponse.of(
                bookingRepository.findAllByOrderByCreatedAtDesc(pageable));
    }

    @Transactional(readOnly = true)
    public com.geekonsites.backend.dto.PageResponse<Booking> getBookingsByCustomerId(
            Long customerId, org.springframework.data.domain.Pageable pageable) {
        return com.geekonsites.backend.dto.PageResponse.of(bookingRepository.findByCustomerId(customerId, pageable));
    }

    @Transactional(readOnly = true)
    public com.geekonsites.backend.dto.PageResponse<Booking> getBookingsByTechnicianId(
            Long technicianId, org.springframework.data.domain.Pageable pageable) {
        return com.geekonsites.backend.dto.PageResponse.of(bookingRepository.findByTechnicianId(technicianId, pageable));
    }

    @Transactional(readOnly = true)
    public com.geekonsites.backend.dto.PageResponse<Booking> getBookingsByAgentId(
            Long agentId, org.springframework.data.domain.Pageable pageable) {
        return com.geekonsites.backend.dto.PageResponse.of(bookingRepository.findByAgentId(agentId, pageable));
    }

    @Transactional(readOnly = true)
    public Booking getBookingById(Long bookingId) {
        // PHASE 9 E2E fix: a missing booking is a 404 (RESOURCE_NOT_FOUND) via the
        // standard error contract, not a 500.
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
    }

    @Transactional(readOnly = true)
    public Booking getBookingForCurrentUser(Long bookingId, User user) {
        Booking booking = getBookingById(bookingId);

        if (user.getRole() == Role.CUSTOMER && !user.getId().equals(booking.getCustomerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not allowed to view this booking");
        }

        if (user.getRole() == Role.TECHNICIAN) {
            var technician = technicianRepository.findAccessByEmail(user.getEmail())
                    .orElseThrow(() -> new RuntimeException("Technician profile not found"));

            if (!technician.getId().equals(booking.getTechnicianId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not assigned to this booking");
            }
        }

        return booking;
    }

    /**
     * PHASE 5 — authorizes invoice generation/mutation BEFORE it happens. Allowed:
     * ADMIN/AGENT (operational) and the owning CUSTOMER. A TECHNICIAN assigned to the
     * booking is explicitly NOT allowed to cause financial-document mutation.
     */
    @Transactional(readOnly = true)
    public Booking authorizeInvoiceAction(Long bookingId, User user) {
        Booking booking = getBookingById(bookingId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (user.getRole() == Role.ADMIN || user.getRole() == Role.AGENT) {
            return booking;
        }
        if (user.getRole() == Role.CUSTOMER && user.getId().equals(booking.getCustomerId())) {
            return booking;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "You are not allowed to generate an invoice for this booking");
    }

    public Booking assignTechnician(Long bookingId, Long technicianId) {

        // Lock the booking first (global lock order), then technicians.
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));

        if (!"PAID".equalsIgnoreCase(booking.getPaymentStatus()) &&
                !(booking.getServiceMode() != null &&
                        booking.getServiceMode().name().equals("ONSITE") &&
                        "PARTIALLY_PAID".equalsIgnoreCase(booking.getPaymentStatus()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A technician can only be assigned after the required payment is confirmed");
        }

        Long previousTechnicianId = booking.getTechnicianId();

        // Lock technicians in deterministic ascending-id order to prevent deadlocks.
        List<Long> technicianIds = new ArrayList<>();
        if (previousTechnicianId != null) technicianIds.add(previousTechnicianId);
        technicianIds.add(technicianId);
        Map<Long, Technician> locked = new HashMap<>();
        technicianIds.stream().filter(Objects::nonNull).distinct().sorted()
                .forEach(id -> locked.put(id, technicianRepository.findByIdForUpdate(id).orElse(null)));

        Technician technician = locked.get(technicianId);
        if (technician == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Technician not found");
        }

        if (!"APPROVED".equalsIgnoreCase(technician.getVerificationStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only approved technicians can be assigned");
        }
        if (!"AVAILABLE".equalsIgnoreCase(technician.getAvailabilityStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This technician is not currently available");
        }

        String technicianMode = technician.getServiceMode() == null
                ? "REMOTE_AND_ONSITE"
                : technician.getServiceMode().toUpperCase();
        ServiceMode bookingMode = booking.getServiceMode();
        boolean incompatible =
                (bookingMode == ServiceMode.REMOTE && "ONSITE_ONLY".equals(technicianMode)) ||
                (bookingMode == ServiceMode.ONSITE && "REMOTE_ONLY".equals(technicianMode)) ||
                (bookingMode == ServiceMode.HYBRID && !"REMOTE_AND_ONSITE".equals(technicianMode));
        if (incompatible) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This technician is not approved for the booking's service mode");
        }

        // Central lifecycle validation + transition (rejects completed/cancelled/closed/active).
        stateMachine.assignTechnician(booking);

        Technician previous = previousTechnicianId == null ? null : locked.get(previousTechnicianId);
        if (previous != null && !previous.getId().equals(technician.getId())) {
            previous.setAvailabilityStatus("AVAILABLE");
            technicianRepository.save(previous);
        }
        if (previous == null || !previous.getId().equals(technician.getId())) {
            technician.setAvailabilityStatus("BUSY");
            technicianRepository.save(technician);
        }

        booking.setTechnicianId(technician.getId());
        booking.setTechnicianName(technician.getName());
        booking.setTechnicianPhone(technician.getPhone());

        Booking savedBooking = bookingRepository.save(booking);

        // AFTER COMMIT: notifications + calendar attendee sync (external, non-critical).
        eventPublisher.publishEvent(new BookingAssignedEvent(savedBooking.getId(), technician.getId()));
        return savedBooking;
    }

    // NOTE (PHASE 1): BookingService.paymentSuccess(...) was removed. It marked a
    // booking paid from a caller-supplied transaction id without Stripe verification
    // (audit C2 / BUG-02). Verified payment finalization now lives in PaymentService.

    public Booking technicianAcceptJob(Long bookingId, Long technicianId) {

        Booking booking = validateTechnicianBooking(bookingId, technicianId);

        boolean changed = stateMachine.technicianAccept(booking);
        if (!changed) {
            return booking;
        }

        Booking savedBooking = bookingRepository.save(booking);

        notificationService.createNotification(
                savedBooking.getCustomerId(),
                NotificationType.TECHNICIAN_ACCEPTED,
                "Job Accepted",
                savedBooking.getTechnicianName() + " accepted your service request.",
                NotificationType.TECHNICIAN_ACCEPTED + ":" + savedBooking.getId()
        );

        return savedBooking;
    }

    public Booking technicianRejectJob(Long bookingId, Long technicianId, String reason) {

        Booking booking = validateTechnicianBooking(bookingId, technicianId);

        boolean changed = stateMachine.technicianReject(booking, reason);
        if (!changed) {
            return booking;
        }

        booking.setTechnicianId(null);
        booking.setTechnicianName(null);
        booking.setTechnicianPhone(null);
        Technician technician = technicianRepository.findByIdForUpdate(technicianId).orElse(null);
        if (technician != null) {
            technician.setAvailabilityStatus("AVAILABLE");
            technicianRepository.save(technician);
        }

        Booking savedBooking = bookingRepository.save(booking);

        notificationService.createNotification(
                savedBooking.getCustomerId(),
                NotificationType.TECHNICIAN_REJECTED,
                "Technician Rejected Job",
                "Your booking will be reassigned to another technician.",
                NotificationType.TECHNICIAN_REJECTED + ":" + savedBooking.getId() + ":" + technicianId
        );

        return savedBooking;
    }

    public Booking technicianOnTheWay(Long bookingId, Long technicianId) {

        Booking booking = validateTechnicianBooking(bookingId, technicianId);

        boolean changed = stateMachine.markOnTheWay(booking);
        if (!changed) {
            return booking;
        }

        Booking savedBooking = bookingRepository.save(booking);

        notificationService.createNotification(
                savedBooking.getCustomerId(),
                NotificationType.TECHNICIAN_ON_THE_WAY,
                "Technician On The Way",
                savedBooking.getTechnicianName() + " is on the way.",
                NotificationType.TECHNICIAN_ON_THE_WAY + ":" + savedBooking.getId()
        );

        return savedBooking;
    }

    public Booking technicianArrived(Long bookingId, Long technicianId) {

        Booking booking = validateTechnicianBooking(bookingId, technicianId);

        boolean changed = stateMachine.markArrived(booking);
        if (!changed) {
            return booking;
        }

        Booking savedBooking = bookingRepository.save(booking);

        notificationService.createNotification(
                savedBooking.getCustomerId(),
                NotificationType.TECHNICIAN_ARRIVED,
                "Technician Arrived",
                savedBooking.getTechnicianName() + " has arrived at your location.",
                NotificationType.TECHNICIAN_ARRIVED + ":" + savedBooking.getId()
        );

        return savedBooking;
    }

    /**
     * PHASE 2 — pure tracking. Updates coordinates/tracking metadata only; it never
     * changes the booking lifecycle (no implicit acceptance, no status regression, no
     * mutation of completed/closed/cancelled bookings).
     */
    public Booking updateTechnicianLocation(
            Long bookingId,
            Long technicianId,
            TechnicianLocationRequest request
    ) {

        Booking booking = validateTechnicianBooking(bookingId, technicianId);

        // Lifecycle must permit tracking before any data is written.
        stateMachine.assertTrackingAllowed(booking);

        validateCoordinates(request);

        booking.setTechnicianLatitude(request.getLatitude());
        booking.setTechnicianLongitude(request.getLongitude());
        booking.setEtaMinutes(request.getEtaMinutes());
        booking.setRemainingDistanceKm(request.getRemainingDistanceKm());
        booking.setTechnicianSpeed(request.getSpeed());
        booking.setTechnicianHeading(request.getHeading());
        booking.setCurrentRoad(request.getCurrentRoad());
        booking.setLiveTrackingStatus(request.getLiveTrackingStatus());
        booking.setLastLocationUpdate(LocalDateTime.now());
        booking.setTrackingEnabled(true);

        if (request.getEtaMinutes() != null) {
            booking.setEstimatedArrivalTime(LocalDateTime.now().plusMinutes(request.getEtaMinutes()));
        }

        return bookingRepository.save(booking);
    }

    public Booking startService(Long bookingId, Long technicianId) {

        Booking booking = validateTechnicianBooking(bookingId, technicianId);
        ukEarlyServiceConsentService.validateBeforeServiceStart(booking);

        boolean changed = stateMachine.startOnsiteService(booking);
        if (!changed) {
            return booking;
        }

        Booking savedBooking = bookingRepository.save(booking);

        notificationService.createNotification(
                savedBooking.getCustomerId(),
                NotificationType.SERVICE_STARTED,
                "Service Started",
                savedBooking.getTechnicianName() + " has started your service.",
                NotificationType.SERVICE_STARTED + ":" + savedBooking.getId()
        );

        return savedBooking;
    }

    public Booking startRemoteSession(Long bookingId, Long technicianId, String remoteSessionLink) {

        Booking booking = validateTechnicianBooking(bookingId, technicianId);
        ukEarlyServiceConsentService.validateBeforeServiceStart(booking);

        if (!"PAID".equalsIgnoreCase(booking.getPaymentStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Full payment is required before starting remote support");
        }

        String meetLink = booking.getRemoteSessionLink();

        if (!isValidGoogleMeetLink(meetLink)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Save a valid Google Meet link before starting the remote session");
        }

        boolean changed = stateMachine.startRemoteSession(booking);
        if (!changed) {
            return booking;
        }

        booking.setRemoteSessionRequired(true);
        booking.setRemoteSessionLink(meetLink);

        Booking savedBooking = bookingRepository.save(booking);

        notificationService.createNotification(
                savedBooking.getCustomerId(),
                NotificationType.REMOTE_SESSION_READY,
                "Remote Session Ready",
                "Your Google Meet remote support session is ready.",
                NotificationType.REMOTE_SESSION_READY + ":" + savedBooking.getId()
        );

        return savedBooking;
    }

public Booking saveMeetingLink(Long bookingId, Long technicianId, String meetingLink) {

    Booking booking = validateTechnicianBooking(bookingId, technicianId);

    if (booking.getServiceMode() == null || !"REMOTE".equals(booking.getServiceMode().name())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Meeting links can only be saved for remote bookings");
    }

    if (!"PAID".equalsIgnoreCase(booking.getPaymentStatus())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Remote support can begin only after full payment is confirmed");
    }

    if (booking.getBookingStatus() != com.geekonsites.backend.enums.BookingStatus.TECHNICIAN_ACCEPTED) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Accept the assigned booking before saving its meeting link");
    }

    if (!isValidGoogleMeetLink(meetingLink)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Google Meet link");
    }

    if (isValidGoogleMeetLink(booking.getRemoteSessionLink()) &&
            !booking.getRemoteSessionLink().equals(meetingLink.trim())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "A Google Meet link is already saved for this booking");
    }

    booking.setRemoteSessionRequired(true);
    booking.setRemoteSessionLink(meetingLink.trim());

    Booking savedBooking = bookingRepository.save(booking);

        notificationService.createNotification(
                savedBooking.getCustomerId(),
                NotificationType.MEETING_LINK_READY,
                "Meeting Link Ready",
                "Your secure remote-support meeting link is ready.",
                NotificationType.MEETING_LINK_READY + ":" + savedBooking.getId()
        );

    return savedBooking;
}

    public Booking completeService(Long bookingId, Long technicianId) {

        Booking booking = validateTechnicianBooking(bookingId, technicianId);

        boolean changed = stateMachine.completeService(booking);
        if (!changed) {
            return booking;
        }

        // PHASE 8 — exact minor-unit comparison (no floating point).
        boolean balanceDue = PaymentMoney.resolveMinor(booking.getRemainingAmountMinor(), booking.getRemainingAmount()) > 0;
        if (balanceDue) {
            // Payment aggregate remains owned by the payment domain; the lifecycle state
            // transition is decided by the state machine.
            stateMachine.markRemainingPaymentPending(booking);
            booking.setPaymentStatus("BALANCE_PENDING");
        }

        Booking savedBooking = bookingRepository.save(booking);

        Technician technician = technicianRepository.findByIdForUpdate(technicianId).orElse(null);
        if (technician != null) {
            technician.setAvailabilityStatus("AVAILABLE");
            technicianRepository.save(technician);
        }

        if (balanceDue) {
            notificationService.createNotification(
                    savedBooking.getCustomerId(),
                    NotificationType.REMAINING_PAYMENT_REQUIRED,
                    "Remaining Payment Required",
                    "Your technician has completed the service. Please pay the remaining balance to close your booking.",
                    NotificationType.REMAINING_PAYMENT_REQUIRED + ":" + savedBooking.getId()
            );
        } else {
            notificationService.createNotification(
                    savedBooking.getCustomerId(),
                    NotificationType.SERVICE_COMPLETED,
                    "Service Completed",
                    "Your service has been completed successfully.",
                    NotificationType.SERVICE_COMPLETED + ":" + savedBooking.getId()
            );
        }
        return savedBooking;
    }

    /**
     * PHASE 9 — thin delegate to the single invoice authority ({@link InvoiceService}).
     * This method no longer contains its own numbering or invoice-creation logic; it only
     * performs the booking lifecycle transition and the (deduped) notification.
     */
    public Booking generateInvoice(Long bookingId) {

        Booking booking = getBookingById(bookingId);

        if (booking.getInvoiceGenerated() != null && booking.getInvoiceGenerated()) {
            return booking;
        }

        invoiceService.generateInvoiceFromBooking(bookingId);

        Booking refreshed = getBookingById(bookingId);
        if (stateMachine.markInvoiceGenerated(refreshed)) {
            refreshed = bookingRepository.save(refreshed);
        }

        notificationService.createNotification(
                refreshed.getCustomerId(),
                NotificationType.INVOICE_GENERATED,
                "Invoice Generated",
                "Your invoice is now available.",
                NotificationType.INVOICE_GENERATED + ":" + refreshed.getId()
        );

        return refreshed;
    }

    // NOTE (PHASE 1): BookingService.remainingPaymentSuccess(...) was removed for the
    // same reason as paymentSuccess(...). The verified Stripe path (FULL on-site
    // remaining payment) is handled by PaymentService.applyCompletedCheckoutSession.

    // NOTE (PHASE 5): BookingService.rateBooking(...) was removed. Rating submission is
    // now owned exclusively by RatingService (single source of truth). The Booking
    // customerRating/customerReview fields remain legacy/unused for new submissions.

    public Booking closeBooking(Long bookingId) {

        Booking booking = getBookingById(bookingId);

        if (!"PAID".equalsIgnoreCase(booking.getPaymentStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Booking cannot be closed before full payment");
        }

        if (booking.getInvoiceGenerated() == null || !booking.getInvoiceGenerated()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Booking cannot be closed before invoice generation");
        }

        boolean changed = stateMachine.closeBooking(booking);
        if (!changed) {
            return booking;
        }

        booking.setBookingClosed(true);
        booking.setBookingClosedAt(LocalDateTime.now());

        Booking savedBooking = bookingRepository.save(booking);

        notificationService.createNotification(
                savedBooking.getCustomerId(),
                NotificationType.BOOKING_CLOSED,
                "Booking Closed",
                "Your booking has been successfully closed.",
                NotificationType.BOOKING_CLOSED + ":" + savedBooking.getId()
        );

        return savedBooking;
    }

    // NOTE (PHASE 2): BookingService.updateStatus(...) was removed. It allowed
    // AGENT/ADMIN callers to set an arbitrary BookingStatus. Lifecycle changes now go
    // through the named business actions backed by BookingStateMachine.

    public List<Booking> getBookingsByCustomerId(Long customerId) {
        return bookingRepository.findByCustomerId(customerId, boundedPage()).getContent();
    }

    public List<Booking> getBookingsByTechnicianId(Long technicianId) {
        return bookingRepository.findByTechnicianId(technicianId, boundedPage()).getContent();
    }

    public List<Booking> getBookingsByAgentId(Long agentId) {
        return bookingRepository.findByAgentId(agentId, boundedPage()).getContent();
    }

    private org.springframework.data.domain.Pageable boundedPage() {
        return org.springframework.data.domain.PageRequest.of(0, 100,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
    }

    public Booking getTracking(Long bookingId) {
        return getBookingById(bookingId);
    }

    public Booking updateCustomerLocation(
        Long bookingId,
        CustomerLocationRequest request
) {

    Booking booking = getBookingById(bookingId);

    booking.setCustomerLatitude(request.getLatitude());
    booking.setCustomerLongitude(request.getLongitude());

    return bookingRepository.save(booking);
}

    private void validateCoordinates(TechnicianLocationRequest request) {
        Double latitude = request.getLatitude();
        Double longitude = request.getLongitude();
        if (latitude != null && (latitude < -90.0 || latitude > 90.0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Latitude must be between -90 and 90");
        }
        if (longitude != null && (longitude < -180.0 || longitude > 180.0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Longitude must be between -180 and 180");
        }
    }

    private Booking validateTechnicianBooking(Long bookingId, Long technicianId) {

        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        if (booking.getTechnicianId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No technician assigned to this booking");
        }

        if (!booking.getTechnicianId().equals(technicianId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This booking is not assigned to this technician");
        }

        return booking;
    }

    private boolean isValidGoogleMeetLink(String meetingLink) {
        if (meetingLink == null || meetingLink.isBlank()) {
            return false;
        }

        return meetingLink.trim().matches(
                "^https://meet\\.google\\.com/[a-zA-Z]{3}-[a-zA-Z]{4}-[a-zA-Z]{3}(?:[?#].*)?$"
        );
    }

}
