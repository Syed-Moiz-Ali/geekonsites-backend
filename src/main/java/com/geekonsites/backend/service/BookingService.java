package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.BookingRequest;
import com.geekonsites.backend.dto.CustomerLocationRequest;
import com.geekonsites.backend.dto.TechnicianLocationRequest;
import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;


import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final TechnicianRepository technicianRepository;
    private final TrustedPricingService pricingService;
    private final NotificationService notificationService;
    private final UkEarlyServiceConsentService ukEarlyServiceConsentService;
    private final RemoteSessionProvisioningService remoteSessionProvisioningService;
    
    public BookingService(
            BookingRepository bookingRepository,
            TechnicianRepository technicianRepository,
            TrustedPricingService pricingService,
            NotificationService notificationService,
            UkEarlyServiceConsentService ukEarlyServiceConsentService,
            RemoteSessionProvisioningService remoteSessionProvisioningService
    ) {
        this.bookingRepository = bookingRepository;
        this.technicianRepository = technicianRepository;
        this.pricingService = pricingService;
        this.notificationService = notificationService;
        this.ukEarlyServiceConsentService = ukEarlyServiceConsentService;
        this.remoteSessionProvisioningService = remoteSessionProvisioningService;
    }

    public Booking createBooking(BookingRequest request) {

        BookingRequest pricedRequest = pricingService.calculatePricing(request);

        Booking booking = new Booking();

        booking.setCustomerId(pricedRequest.getCustomerId());
        booking.setCustomerName(pricedRequest.getCustomerName());
        booking.setCustomerEmail(pricedRequest.getCustomerEmail());
        booking.setCustomerPhone(pricedRequest.getCustomerPhone());

        booking.setServiceType(pricedRequest.getServiceType());
        booking.setServiceMode(pricedRequest.getServiceMode());
        booking.setIssueDescription(pricedRequest.getIssueDescription());

        booking.setAddress(pricedRequest.getAddress());
        booking.setCity(pricedRequest.getCity());
        booking.setState(pricedRequest.getState());
        booking.setPostalCode(pricedRequest.getPostalCode());
        booking.setCountry(pricedRequest.getCountry());
        booking.setCustomerLatitude(pricedRequest.getCustomerLatitude());
        booking.setCustomerLongitude(pricedRequest.getCustomerLongitude());

        if (pricedRequest.getBookingDate() != null &&
                !pricedRequest.getBookingDate().isBlank()) {
            booking.setBookingDate(LocalDate.parse(pricedRequest.getBookingDate()));
        }

        booking.setTimeSlot(pricedRequest.getTimeSlot());
        booking.setCurrency(pricedRequest.getCurrency());

        booking.setBaseAmount(pricedRequest.getBaseAmount());
        booking.setAddonsAmount(pricedRequest.getAddonsAmount());
        booking.setProtectionAmount(pricedRequest.getProtectionAmount());
        booking.setPlatformFee(pricedRequest.getPlatformFee());
        booking.setTotalAmount(pricedRequest.getTotalAmount());

        booking.setAdvanceAmount(pricedRequest.getAdvanceAmount());
        booking.setRemainingAmount(pricedRequest.getRemainingAmount());
        booking.setPaidAmount(0.0);

        booking.setPaymentType(pricedRequest.getPaymentType());
        booking.setPaymentStatus("PENDING");
        booking.setPaymentMethod(pricedRequest.getPaymentMethod());
        booking.setPaymentTransactionId(null);

        booking.setSelectedAddons(pricedRequest.getSelectedAddons());
        booking.setProtectionPlan(pricedRequest.getProtectionPlan());

        booking.setCustomerLatitude(pricedRequest.getCustomerLatitude());
        booking.setCustomerLongitude(pricedRequest.getCustomerLongitude());

        booking.setRemoteSessionRequired(
                pricedRequest.getRemoteSessionRequired() != null
                        ? pricedRequest.getRemoteSessionRequired()
                        : false
        );

        booking.setRemoteSessionLink(null);
        booking.setInvoiceGenerated(false);
        booking.setBookingClosed(false);
        booking.setBookingStatus(BookingStatus.PENDING);

        Booking savedBooking = bookingRepository.save(booking);

        notificationService.createNotification(
        savedBooking.getCustomerId(),
        "Booking Created",
        "Your " + savedBooking.getServiceType() + " booking has been created successfully."
);

        return savedBooking;
    }

    public List<Booking> getAllBookings() {
        return bookingRepository.findAll();
    }

    public Booking getBookingById(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));
    }

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

    public Booking assignTechnician(Long bookingId, Long technicianId) {

        Booking booking = getBookingById(bookingId);

        if (!"PAID".equalsIgnoreCase(booking.getPaymentStatus()) &&
                !(booking.getServiceMode() != null &&
                        booking.getServiceMode().name().equals("ONSITE") &&
                        "PARTIALLY_PAID".equalsIgnoreCase(booking.getPaymentStatus()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A technician can only be assigned after the required payment is confirmed");
        }

        Technician technician = technicianRepository.findById(technicianId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Technician not found"));

        if (!"APPROVED".equalsIgnoreCase(technician.getVerificationStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only approved technicians can be assigned");
        }
        if (!"AVAILABLE".equalsIgnoreCase(technician.getAvailabilityStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This technician is not currently available");
        }

        // Service-mode compatibility is a business validation, not a server
        // error: it is preserved exactly, but is now reported as 400 Bad
        // Request (see BookingControllerAssignTechnicianTest) instead of an
        // uncaught RuntimeException, which previously surfaced to callers
        // (including the Agent Assign Technician UI) as a generic 500.
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

        booking.setTechnicianId(technician.getId());
        booking.setTechnicianName(technician.getName());
        booking.setTechnicianPhone(technician.getPhone());
        booking.setBookingStatus(BookingStatus.TECHNICIAN_ASSIGNED);
        technician.setAvailabilityStatus("BUSY");
        technicianRepository.save(technician);

        Booking savedBooking = bookingRepository.save(booking);
        savedBooking = remoteSessionProvisioningService.syncAssignedParticipantsBeforeStart(savedBooking);

        notificationService.createNotification(
                savedBooking.getCustomerId(),
                "Technician Assigned",
                technician.getName() + " has been assigned to your booking."
        );

        notificationService.createTechnicianNotification(
                technician.getId(),
                "New Job Assigned",
                "You have been assigned to booking GOS-" + savedBooking.getId() + " (" + savedBooking.getServiceType() + ")."
        );

        return savedBooking;
    }

    public Booking paymentSuccess(Long bookingId, String transactionId, String paymentMethod) {

        Booking booking = getBookingById(bookingId);

        booking.setPaymentTransactionId(transactionId);
        booking.setPaymentMethod(paymentMethod);

        if ("ADVANCE_PAYMENT".equalsIgnoreCase(booking.getPaymentType())) {
            booking.setPaidAmount(booking.getAdvanceAmount());
            booking.setPaymentStatus("PARTIALLY_PAID");
            booking.setBookingStatus(BookingStatus.ASSIGNMENT_PENDING);
        } else {
            booking.setPaidAmount(booking.getTotalAmount());
            booking.setPaymentStatus("PAID");
            booking.setBookingStatus(BookingStatus.PAYMENT_COMPLETED);
        }

        Booking savedBooking = bookingRepository.save(booking);

        notificationService.createPaymentSuccessNotification(
                savedBooking,
                booking.getPaymentType() != null && booking.getPaymentType().toUpperCase().contains("ADVANCE") ? "ADVANCE" : "FULL",
                transactionId
        );

        return savedBooking;
    }

    public Booking technicianAcceptJob(Long bookingId, Long technicianId) {

        Booking booking = validateTechnicianBooking(bookingId, technicianId);

        if (booking.getBookingStatus() != BookingStatus.TECHNICIAN_ASSIGNED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only assigned jobs can be accepted");
        }

        booking.setBookingStatus(BookingStatus.TECHNICIAN_ACCEPTED);
        booking.setTechnicianAcceptedAt(LocalDateTime.now());

        Booking savedBooking = bookingRepository.save(booking);

        notificationService.createNotification(
                savedBooking.getCustomerId(),
                "Job Accepted",
                savedBooking.getTechnicianName() + " accepted your service request."
        );

        return savedBooking;
    }

    public Booking technicianRejectJob(Long bookingId, Long technicianId, String reason) {

        Booking booking = validateTechnicianBooking(bookingId, technicianId);

        if (booking.getBookingStatus() != BookingStatus.TECHNICIAN_ASSIGNED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only assigned jobs can be rejected");
        }

        booking.setBookingStatus(BookingStatus.TECHNICIAN_REJECTED);
        booking.setTechnicianRejectedAt(LocalDateTime.now());
        booking.setTechnicianRejectReason(
                reason != null && !reason.isBlank()
                        ? reason
                        : "No reason provided"
        );

        booking.setTechnicianId(null);
        booking.setTechnicianName(null);
        booking.setTechnicianPhone(null);
        Technician technician = technicianRepository.findById(technicianId).orElse(null);
        if (technician != null) {
            technician.setAvailabilityStatus("AVAILABLE");
            technicianRepository.save(technician);
        }

        Booking savedBooking = bookingRepository.save(booking);

        notificationService.createNotification(
                savedBooking.getCustomerId(),
                "Technician Rejected Job",
                "Your booking will be reassigned to another technician."
        );

        return savedBooking;
    }

    public Booking technicianOnTheWay(Long bookingId, Long technicianId) {

        Booking booking = validateTechnicianBooking(bookingId, technicianId);

        if (booking.getBookingStatus() != BookingStatus.TECHNICIAN_ACCEPTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Technician cannot start travel for this booking");
        }

        booking.setBookingStatus(BookingStatus.TECHNICIAN_ON_THE_WAY);
        booking.setTechnicianOnTheWayAt(LocalDateTime.now());

        Booking savedBooking = bookingRepository.save(booking);

        notificationService.createNotification(
                savedBooking.getCustomerId(),
                "Technician On The Way",
                savedBooking.getTechnicianName() + " is on the way."
        );

        return savedBooking;
    }

    public Booking technicianArrived(Long bookingId, Long technicianId) {

    Booking booking = validateTechnicianBooking(bookingId, technicianId);

    if (booking.getBookingStatus() != BookingStatus.TECHNICIAN_ON_THE_WAY) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Technician is not on the way");
    }

    booking.setTechnicianArrived(true);
    booking.setLiveTrackingStatus("ARRIVED");
    booking.setTrackingEnabled(false);
    booking.setBookingStatus(BookingStatus.TECHNICIAN_ARRIVED);

    Booking savedBooking = bookingRepository.save(booking);

    notificationService.createNotification(
            savedBooking.getCustomerId(),
            "Technician Arrived",
            savedBooking.getTechnicianName() + " has arrived at your location."
    );

    return savedBooking;
}

   public Booking updateTechnicianLocation(
        Long bookingId,
        Long technicianId,
        TechnicianLocationRequest request
) {

    Booking booking = validateTechnicianBooking(bookingId, technicianId);

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

        booking.setEstimatedArrivalTime(
                LocalDateTime.now().plusMinutes(request.getEtaMinutes())
        );
    }
    
    if (request.getRemainingDistanceKm() != null &&
        request.getRemainingDistanceKm() <= 0.10) {

    if (!Boolean.TRUE.equals(booking.getTechnicianArrived())) {

        booking.setTechnicianArrived(true);

        booking.setLiveTrackingStatus("ARRIVED");

        notificationService.createNotification(
                booking.getCustomerId(),
                "Technician Arrived",
                booking.getTechnicianName() + " has arrived at your location."
        );
    }

} else {

    booking.setTechnicianArrived(false);
}

    if (booking.getBookingStatus() != BookingStatus.SERVICE_STARTED &&
            booking.getBookingStatus() != BookingStatus.REMOTE_SESSION_STARTED &&
            booking.getBookingStatus() != BookingStatus.SERVICE_COMPLETED &&
            booking.getBookingStatus() != BookingStatus.BOOKING_CLOSED) {

        booking.setBookingStatus(BookingStatus.TECHNICIAN_ON_THE_WAY);

        if (booking.getTechnicianOnTheWayAt() == null) {

            booking.setTechnicianOnTheWayAt(LocalDateTime.now());
        }
    }

Booking savedBooking = bookingRepository.save(booking);

return savedBooking;
}

  public Booking startService(Long bookingId, Long technicianId) {

    Booking booking = validateTechnicianBooking(bookingId, technicianId);
    ukEarlyServiceConsentService.validateBeforeServiceStart(booking);

    if (booking.getBookingStatus() != BookingStatus.TECHNICIAN_ARRIVED) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The technician must arrive before starting an on-site service");
    }

    booking.setBookingStatus(BookingStatus.SERVICE_STARTED);
    booking.setServiceStartedAt(LocalDateTime.now());

    Booking savedBooking = bookingRepository.save(booking);

    notificationService.createNotification(
            savedBooking.getCustomerId(),
            "Service Started",
            savedBooking.getTechnicianName() + " has started your service."
    );

    return savedBooking;
}

    public Booking startRemoteSession(Long bookingId, Long technicianId, String remoteSessionLink) {

    Booking booking = validateTechnicianBooking(bookingId, technicianId);
    ukEarlyServiceConsentService.validateBeforeServiceStart(booking);

    if (!"PAID".equalsIgnoreCase(booking.getPaymentStatus())) {
        throw new RuntimeException("Full payment is required before starting remote support");
    }

    if (booking.getBookingStatus() != BookingStatus.TECHNICIAN_ACCEPTED) {
        throw new RuntimeException("Remote session cannot be started now");
    }

    String meetLink = booking.getRemoteSessionLink();

    if (!isValidGoogleMeetLink(meetLink)) {
        throw new RuntimeException("Save a valid Google Meet link before starting the remote session");
    }

    booking.setRemoteSessionRequired(true);
    booking.setRemoteSessionLink(meetLink);
    booking.setRemoteSessionStartedAt(LocalDateTime.now());
    booking.setBookingStatus(BookingStatus.REMOTE_SESSION_STARTED);

    Booking savedBooking = bookingRepository.save(booking);

    notificationService.createNotification(
            savedBooking.getCustomerId(),
            "Remote Session Ready",
            "Your Google Meet remote support session is ready."
    );

    return savedBooking;
}

public Booking saveMeetingLink(Long bookingId, Long technicianId, String meetingLink) {

    Booking booking = validateTechnicianBooking(bookingId, technicianId);

    if (booking.getServiceMode() == null || !"REMOTE".equals(booking.getServiceMode().name())) {
        throw new RuntimeException("Meeting links can only be saved for remote bookings");
    }

    if (!"PAID".equalsIgnoreCase(booking.getPaymentStatus())) {
        throw new RuntimeException("Remote support can begin only after full payment is confirmed");
    }

    if (booking.getBookingStatus() != BookingStatus.TECHNICIAN_ACCEPTED) {
        throw new RuntimeException("Accept the assigned booking before saving its meeting link");
    }

    if (!isValidGoogleMeetLink(meetingLink)) {
        throw new RuntimeException("Invalid Google Meet link");
    }

    if (isValidGoogleMeetLink(booking.getRemoteSessionLink()) &&
            !booking.getRemoteSessionLink().equals(meetingLink.trim())) {
        throw new RuntimeException("A Google Meet link is already saved for this booking");
    }

    booking.setRemoteSessionRequired(true);
    booking.setRemoteSessionLink(meetingLink.trim());

    Booking savedBooking = bookingRepository.save(booking);

    notificationService.createNotification(
            savedBooking.getCustomerId(),
            "Meeting Link Ready",
            "Your secure remote-support meeting link is ready."
    );

    return savedBooking;
}

    public Booking completeService(Long bookingId, Long technicianId) {

        Booking booking = validateTechnicianBooking(bookingId, technicianId);

        if (booking.getBookingStatus() != BookingStatus.SERVICE_STARTED &&
                booking.getBookingStatus() != BookingStatus.REMOTE_SESSION_STARTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Service cannot be completed now");
        }

        booking.setBookingStatus(BookingStatus.SERVICE_COMPLETED);
        booking.setServiceCompletedAt(LocalDateTime.now());

        if (booking.getRemoteSessionStartedAt() != null) {
            booking.setRemoteSessionEndedAt(LocalDateTime.now());
        }

        if (booking.getRemainingAmount() != null && booking.getRemainingAmount() > 0) {
            booking.setPaymentStatus("BALANCE_PENDING");
            booking.setBookingStatus(BookingStatus.REMAINING_PAYMENT_PENDING);
        }

        Booking savedBooking = bookingRepository.save(booking);

        Technician technician = technicianRepository.findById(technicianId).orElse(null);
        if (technician != null) {
            technician.setAvailabilityStatus("AVAILABLE");
            technicianRepository.save(technician);
        }

        if (booking.getRemainingAmount() != null && booking.getRemainingAmount() > 0) {

    notificationService.createNotification(
            savedBooking.getCustomerId(),
            "Remaining Payment Required",
            "Your technician has completed the service. Please pay the remaining balance to close your booking."
    );

} else {

    notificationService.createNotification(
            savedBooking.getCustomerId(),
            "Service Completed",
            "Your service has been completed successfully."
    );
}
        return savedBooking;
    }

    public Booking generateInvoice(Long bookingId) {

        Booking booking = getBookingById(bookingId);

        if (booking.getInvoiceGenerated() != null && booking.getInvoiceGenerated()) {
            return booking;
        }

        String invoiceNumber = String.format(
    "GOS-2026-%06d",
    booking.getId()
);

        booking.setInvoiceNumber(invoiceNumber);
        booking.setInvoiceGenerated(true);
        booking.setInvoiceGeneratedAt(LocalDateTime.now());

        if (booking.getBookingStatus() == BookingStatus.SERVICE_COMPLETED) {
            booking.setBookingStatus(BookingStatus.INVOICE_GENERATED);
        }

        Booking savedBooking = bookingRepository.save(booking);

        notificationService.createNotification(
                savedBooking.getCustomerId(),
                "Invoice Generated",
                "Your invoice is now available."
        );

        return savedBooking;
    }

    public Booking remainingPaymentSuccess(Long bookingId, String transactionId, String paymentMethod) {

        Booking booking = getBookingById(bookingId);

        double paid = booking.getPaidAmount() != null ? booking.getPaidAmount() : 0.0;
        double remaining = booking.getRemainingAmount() != null ? booking.getRemainingAmount() : 0.0;

        booking.setPaidAmount(paid + remaining);
        booking.setRemainingAmount(0.0);
        booking.setPaymentStatus("PAID");
        booking.setPaymentTransactionId(transactionId);
        booking.setPaymentMethod(paymentMethod);
        booking.setBookingStatus(BookingStatus.FULLY_PAID);
        booking.setInvoiceGenerated(true);
        booking.setInvoiceGeneratedAt(LocalDateTime.now());

         booking.setInvoiceNumber(
        String.format("GOS-2026-%06d", booking.getId())
      );
        
       booking.setBookingClosed(true);
       booking.setBookingClosedAt(LocalDateTime.now());

        Booking savedBooking = bookingRepository.save(booking);

        notificationService.createPaymentSuccessNotification(savedBooking, "REMAINING", transactionId);

        return savedBooking;
    }

    public Booking rateBooking(Long bookingId, Long customerId, Integer rating, String review) {

        Booking booking = getBookingById(bookingId);

        if (!booking.getCustomerId().equals(customerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not allowed to rate this booking");
        }

        if (booking.getBookingStatus() != BookingStatus.SERVICE_COMPLETED
                && booking.getBookingStatus() != BookingStatus.REMAINING_PAYMENT_PENDING
                && booking.getBookingStatus() != BookingStatus.FULLY_PAID
                && booking.getBookingStatus() != BookingStatus.INVOICE_GENERATED
                && booking.getBookingStatus() != BookingStatus.BOOKING_CLOSED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A booking can be rated only after service completion");
        }

        if (rating == null || rating < 1 || rating > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rating must be between 1 and 5");
        }

        booking.setCustomerRating(rating);
        booking.setCustomerReview(review);
        booking.setRatedAt(LocalDateTime.now());

        return bookingRepository.save(booking);
    }

    public Booking closeBooking(Long bookingId) {

        Booking booking = getBookingById(bookingId);

        if (!"PAID".equalsIgnoreCase(booking.getPaymentStatus())) {
            throw new RuntimeException("Booking cannot be closed before full payment");
        }

        if (booking.getInvoiceGenerated() == null || !booking.getInvoiceGenerated()) {
            throw new RuntimeException("Booking cannot be closed before invoice generation");
        }

        booking.setBookingClosed(true);
        booking.setBookingClosedAt(LocalDateTime.now());
        booking.setBookingStatus(BookingStatus.BOOKING_CLOSED);

        Booking savedBooking = bookingRepository.save(booking);

        notificationService.createNotification(
                savedBooking.getCustomerId(),
                "Booking Closed",
                "Your booking has been successfully closed."
        );

        return savedBooking;
    }

    public Booking updateStatus(Long bookingId, BookingStatus status) {

        Booking booking = getBookingById(bookingId);
        booking.setBookingStatus(status);

        return bookingRepository.save(booking);
    }

    public List<Booking> getBookingsByCustomerId(Long customerId) {
        return bookingRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    public List<Booking> getBookingsByTechnicianId(Long technicianId) {
        return bookingRepository.findByTechnicianIdOrderByCreatedAtDesc(technicianId);
    }

    public List<Booking> getBookingsByAgentId(Long agentId) {
        return bookingRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
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

    private Booking validateTechnicianBooking(Long bookingId, Long technicianId) {

        Booking booking = getBookingById(bookingId);

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
