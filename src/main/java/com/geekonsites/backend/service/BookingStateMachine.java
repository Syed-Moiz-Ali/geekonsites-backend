package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.PaymentType;
import com.geekonsites.backend.enums.ServiceMode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * PHASE 2 — the single authoritative booking lifecycle authority.
 *
 * <p>Every legal booking status transition is decided here. Business-flow code
 * (services/controllers) expresses a <em>business action</em> (accept, arrive, start,
 * complete, close, …); this class validates the current state, the service mode and the
 * financial prerequisites, then performs the status change and the lifecycle
 * timestamps. No other production component should call
 * {@code booking.setBookingStatus(...)}.
 *
 * <p>This component is intentionally small and has no repository dependencies, so it is
 * cheap to unit test. Persistence, notifications and technician-availability side
 * effects are orchestrated by the calling application services.
 */
@Service
public class BookingStateMachine {

    /** States from which a (re)assignment may occur. */
    private static final Set<BookingStatus> ASSIGN_ELIGIBLE = EnumSet.of(
            BookingStatus.PAYMENT_COMPLETED,
            BookingStatus.ASSIGNMENT_PENDING,
            BookingStatus.TECHNICIAN_REJECTED,
            BookingStatus.TECHNICIAN_ASSIGNED
    );

    /** Only a genuinely completed, financially settled booking may be closed. */
    private static final Set<BookingStatus> CLOSE_ELIGIBLE = EnumSet.of(
            BookingStatus.SERVICE_COMPLETED,
            BookingStatus.FULLY_PAID,
            BookingStatus.INVOICE_GENERATED
    );

    /** Lifecycle states in which live tracking must not be mutated. */
    private static final Set<BookingStatus> TRACKING_BLOCKED = EnumSet.of(
            BookingStatus.SERVICE_COMPLETED,
            BookingStatus.INVOICE_GENERATED,
            BookingStatus.REMAINING_PAYMENT_PENDING,
            BookingStatus.FULLY_PAID,
            BookingStatus.BOOKING_CLOSED,
            BookingStatus.CANCELLED
    );

    // =====================================================================
    // Assignment / acceptance
    // =====================================================================

    /**
     * Assign (or reassign) a technician. Technician eligibility itself is validated by
     * the caller; here we only enforce that the booking is in a state where assignment
     * is legal, and we reset the previous acceptance/rejection evidence.
     */
    public void assignTechnician(Booking booking) {
        requireNotTerminal(booking);
        if (!ASSIGN_ELIGIBLE.contains(booking.getBookingStatus())) {
            throw conflict("A technician cannot be assigned in the current booking state");
        }
        booking.setBookingStatus(BookingStatus.TECHNICIAN_ASSIGNED);
        booking.setTechnicianAcceptedAt(null);
        booking.setTechnicianRejectedAt(null);
        booking.setTechnicianRejectReason(null);
    }

    /** @return true when the state changed; false when already accepted (idempotent). */
    public boolean technicianAccept(Booking booking) {
        requireNotTerminal(booking);
        if (booking.getBookingStatus() == BookingStatus.TECHNICIAN_ACCEPTED) {
            return false;
        }
        if (booking.getBookingStatus() != BookingStatus.TECHNICIAN_ASSIGNED) {
            throw badRequest("Only assigned jobs can be accepted");
        }
        booking.setBookingStatus(BookingStatus.TECHNICIAN_ACCEPTED);
        if (booking.getTechnicianAcceptedAt() == null) {
            booking.setTechnicianAcceptedAt(LocalDateTime.now());
        }
        return true;
    }

    /** @return true when the state changed; false when already rejected (idempotent). */
    public boolean technicianReject(Booking booking, String reason) {
        requireNotTerminal(booking);
        if (booking.getBookingStatus() == BookingStatus.TECHNICIAN_REJECTED) {
            return false;
        }
        if (booking.getBookingStatus() != BookingStatus.TECHNICIAN_ASSIGNED) {
            throw badRequest("Only assigned jobs can be rejected");
        }
        booking.setBookingStatus(BookingStatus.TECHNICIAN_REJECTED);
        if (booking.getTechnicianRejectedAt() == null) {
            booking.setTechnicianRejectedAt(LocalDateTime.now());
        }
        booking.setTechnicianRejectReason(
                reason == null || reason.isBlank() ? "No reason provided" : reason);
        return true;
    }

    // =====================================================================
    // On-site travel
    // =====================================================================

    public boolean markOnTheWay(Booking booking) {
        requireNotTerminal(booking);
        requireOnsiteLike(booking, "Travel tracking is only available for on-site services");
        if (booking.getBookingStatus() == BookingStatus.TECHNICIAN_ON_THE_WAY) {
            return false;
        }
        if (booking.getBookingStatus() != BookingStatus.TECHNICIAN_ACCEPTED) {
            throw badRequest("Technician cannot start travel for this booking");
        }
        booking.setBookingStatus(BookingStatus.TECHNICIAN_ON_THE_WAY);
        if (booking.getTechnicianOnTheWayAt() == null) {
            booking.setTechnicianOnTheWayAt(LocalDateTime.now());
        }
        return true;
    }

    public boolean markArrived(Booking booking) {
        requireNotTerminal(booking);
        requireOnsiteLike(booking, "Physical arrival is only available for on-site services");
        if (booking.getBookingStatus() == BookingStatus.TECHNICIAN_ARRIVED) {
            return false;
        }
        if (booking.getBookingStatus() != BookingStatus.TECHNICIAN_ON_THE_WAY) {
            throw badRequest("Technician is not on the way");
        }
        booking.setTechnicianArrived(true);
        booking.setLiveTrackingStatus("ARRIVED");
        booking.setTrackingEnabled(false);
        booking.setBookingStatus(BookingStatus.TECHNICIAN_ARRIVED);
        return true;
    }

    // =====================================================================
    // Service execution
    // =====================================================================

    public boolean startOnsiteService(Booking booking) {
        requireNotTerminal(booking);
        requireOnsiteLike(booking, "On-site service can only be started for on-site bookings");
        if (booking.getBookingStatus() == BookingStatus.SERVICE_STARTED) {
            return false;
        }
        if (booking.getBookingStatus() != BookingStatus.TECHNICIAN_ARRIVED) {
            throw badRequest("The technician must arrive before starting an on-site service");
        }
        booking.setBookingStatus(BookingStatus.SERVICE_STARTED);
        if (booking.getServiceStartedAt() == null) {
            booking.setServiceStartedAt(LocalDateTime.now());
        }
        return true;
    }

    public boolean startRemoteSession(Booking booking) {
        requireNotTerminal(booking);
        requireRemote(booking, "Remote sessions are only available for remote bookings");
        if (booking.getBookingStatus() == BookingStatus.REMOTE_SESSION_STARTED) {
            return false;
        }
        if (booking.getBookingStatus() != BookingStatus.TECHNICIAN_ACCEPTED) {
            throw conflict("Remote session cannot be started now");
        }
        booking.setBookingStatus(BookingStatus.REMOTE_SESSION_STARTED);
        if (booking.getRemoteSessionStartedAt() == null) {
            booking.setRemoteSessionStartedAt(LocalDateTime.now());
        }
        return true;
    }

    /**
     * Complete an active service. Valid for an on-site service that has started or a
     * remote session that has started. Balance handling (moving to
     * {@code REMAINING_PAYMENT_PENDING}) is a separate explicit step requested by the
     * caller once it knows the balance from the payment ledger.
     */
    public boolean completeService(Booking booking) {
        requireNotTerminal(booking);
        BookingStatus status = booking.getBookingStatus();
        if (status == BookingStatus.SERVICE_COMPLETED
                || status == BookingStatus.REMAINING_PAYMENT_PENDING
                || status == BookingStatus.FULLY_PAID
                || status == BookingStatus.INVOICE_GENERATED) {
            return false;
        }
        if (status != BookingStatus.SERVICE_STARTED
                && status != BookingStatus.REMOTE_SESSION_STARTED) {
            throw badRequest("Service cannot be completed now");
        }
        booking.setBookingStatus(BookingStatus.SERVICE_COMPLETED);
        if (booking.getServiceCompletedAt() == null) {
            booking.setServiceCompletedAt(LocalDateTime.now());
        }
        if (booking.getRemoteSessionStartedAt() != null && booking.getRemoteSessionEndedAt() == null) {
            booking.setRemoteSessionEndedAt(LocalDateTime.now());
        }
        return true;
    }

    public boolean markRemainingPaymentPending(Booking booking) {
        requireNotTerminal(booking);
        if (booking.getBookingStatus() == BookingStatus.REMAINING_PAYMENT_PENDING) {
            return false;
        }
        if (booking.getBookingStatus() != BookingStatus.SERVICE_COMPLETED) {
            throw conflict("Remaining payment can only be requested after service completion");
        }
        booking.setBookingStatus(BookingStatus.REMAINING_PAYMENT_PENDING);
        return true;
    }

    // =====================================================================
    // Payment-driven transitions (financial truth owned by PaymentService)
    // =====================================================================

    /**
     * Applies the booking state that follows a verified payment. The payment amount,
     * currency and idempotency are decided by {@code PaymentService} against the
     * {@code PaymentTransaction} ledger; this method only decides the resulting
     * booking operational state.
     */
    public void onPaymentConfirmed(Booking booking, PaymentType type) {
        requireNotTerminal(booking);
        switch (type) {
            case ADVANCE -> booking.setBookingStatus(BookingStatus.ASSIGNMENT_PENDING);
            case REMAINING -> booking.setBookingStatus(BookingStatus.SERVICE_COMPLETED);
            case FULL -> booking.setBookingStatus(BookingStatus.PAYMENT_COMPLETED);
        }
    }

    // =====================================================================
    // Document / closure
    // =====================================================================

    public boolean markInvoiceGenerated(Booking booking) {
        if (booking.getBookingStatus() != BookingStatus.SERVICE_COMPLETED) {
            return false;
        }
        booking.setBookingStatus(BookingStatus.INVOICE_GENERATED);
        return true;
    }

    public boolean closeBooking(Booking booking) {
        BookingStatus status = booking.getBookingStatus();
        if (status == BookingStatus.BOOKING_CLOSED) {
            return false;
        }
        if (status == BookingStatus.CANCELLED) {
            throw conflict("A cancelled booking cannot be closed");
        }
        if (!CLOSE_ELIGIBLE.contains(status)) {
            throw conflict("Booking cannot be closed before the service is completed");
        }
        booking.setBookingStatus(BookingStatus.BOOKING_CLOSED);
        if (booking.getBookingClosedAt() == null) {
            booking.setBookingClosedAt(LocalDateTime.now());
        }
        return true;
    }

    // =====================================================================
    // Tracking guard (tracking must never mutate lifecycle)
    // =====================================================================

    public void assertTrackingAllowed(Booking booking) {
        requireNotTerminal(booking);
        if (booking.getBookingStatus() == null || TRACKING_BLOCKED.contains(booking.getBookingStatus())) {
            throw conflict("Live tracking is not available in the current booking state");
        }
    }

    // =====================================================================
    // Guards
    // =====================================================================

    private void requireNotTerminal(Booking booking) {
        BookingStatus status = booking.getBookingStatus();
        if (status == BookingStatus.CANCELLED) {
            throw conflict("This booking has been cancelled");
        }
        if (status == BookingStatus.BOOKING_CLOSED) {
            throw conflict("This booking is already closed");
        }
    }

    private void requireOnsiteLike(Booking booking, String message) {
        if (booking.getServiceMode() == ServiceMode.REMOTE) {
            throw badRequest(message);
        }
    }

    private void requireRemote(Booking booking, String message) {
        if (booking.getServiceMode() != ServiceMode.REMOTE) {
            throw badRequest(message);
        }
    }

    private InvalidBookingTransitionException conflict(String reason) {
        return new InvalidBookingTransitionException(HttpStatus.CONFLICT, reason);
    }

    private InvalidBookingTransitionException badRequest(String reason) {
        return new InvalidBookingTransitionException(HttpStatus.BAD_REQUEST, reason);
    }
}
