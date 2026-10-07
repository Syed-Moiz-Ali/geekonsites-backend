package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.PaymentType;
import com.geekonsites.backend.enums.ServiceMode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PHASE 2 — focused unit tests for the central {@link BookingStateMachine}: valid
 * transitions, invalid/backward transitions, mode guards, terminal states, payment
 * guards, timestamps and idempotency.
 */
class BookingStateMachineTest {

    private final BookingStateMachine machine = new BookingStateMachine();

    private Booking booking(ServiceMode mode, BookingStatus status) {
        Booking booking = new Booking();
        booking.setServiceMode(mode);
        booking.setBookingStatus(status);
        booking.setPaymentStatus("PAID");
        return booking;
    }

    // ------------------------------------------------------------------ valid

    @Test
    void fullOnsiteChainTransitionsAndTimestamps() {
        Booking booking = booking(ServiceMode.ONSITE, BookingStatus.PAYMENT_COMPLETED);

        machine.assignTechnician(booking);
        assertEquals(BookingStatus.TECHNICIAN_ASSIGNED, booking.getBookingStatus());

        assertTrue(machine.technicianAccept(booking));
        assertEquals(BookingStatus.TECHNICIAN_ACCEPTED, booking.getBookingStatus());
        assertNotNull(booking.getTechnicianAcceptedAt());

        assertTrue(machine.markOnTheWay(booking));
        assertEquals(BookingStatus.TECHNICIAN_ON_THE_WAY, booking.getBookingStatus());
        assertNotNull(booking.getTechnicianOnTheWayAt());

        assertTrue(machine.markArrived(booking));
        assertEquals(BookingStatus.TECHNICIAN_ARRIVED, booking.getBookingStatus());
        assertTrue(booking.getTechnicianArrived());
        assertFalse(booking.getTrackingEnabled());

        assertTrue(machine.startOnsiteService(booking));
        assertEquals(BookingStatus.SERVICE_STARTED, booking.getBookingStatus());
        assertNotNull(booking.getServiceStartedAt());

        assertTrue(machine.completeService(booking));
        assertEquals(BookingStatus.SERVICE_COMPLETED, booking.getBookingStatus());
        assertNotNull(booking.getServiceCompletedAt());

        assertTrue(machine.closeBooking(booking));
        assertEquals(BookingStatus.BOOKING_CLOSED, booking.getBookingStatus());
        assertNotNull(booking.getBookingClosedAt());
    }

    @Test
    void remoteChainTransitions() {
        Booking booking = booking(ServiceMode.REMOTE, BookingStatus.PAYMENT_COMPLETED);

        machine.assignTechnician(booking);
        assertTrue(machine.technicianAccept(booking));
        assertTrue(machine.startRemoteSession(booking));
        assertEquals(BookingStatus.REMOTE_SESSION_STARTED, booking.getBookingStatus());
        assertNotNull(booking.getRemoteSessionStartedAt());

        assertTrue(machine.completeService(booking));
        assertEquals(BookingStatus.SERVICE_COMPLETED, booking.getBookingStatus());
        assertNotNull(booking.getRemoteSessionEndedAt());
    }

    // ---------------------------------------------------------------- invalid

    @Test
    void backwardAndIllegalTransitionsAreRejected() {
        assertEquals(400, transitionError(
                booking(ServiceMode.ONSITE, BookingStatus.TECHNICIAN_ARRIVED), machine::technicianAccept));
        assertEquals(400, transitionError(
                booking(ServiceMode.ONSITE, BookingStatus.TECHNICIAN_ASSIGNED), machine::markOnTheWay));
        assertEquals(400, transitionError(
                booking(ServiceMode.ONSITE, BookingStatus.TECHNICIAN_ACCEPTED), machine::markArrived));
        assertEquals(400, transitionError(
                booking(ServiceMode.ONSITE, BookingStatus.TECHNICIAN_ON_THE_WAY), machine::startOnsiteService));
        // Starting an already-started service is an idempotent no-op, not an error.
        assertFalse(machine.startOnsiteService(booking(ServiceMode.ONSITE, BookingStatus.SERVICE_STARTED)));
    }

    // ------------------------------------------------------------------ mode

    @Test
    void modeSpecificTransitionsAreEnforced() {
        // Remote session cannot start for an on-site booking (even if accepted).
        assertThrows(InvalidBookingTransitionException.class,
                () -> machine.startRemoteSession(booking(ServiceMode.ONSITE, BookingStatus.TECHNICIAN_ACCEPTED)));
        // On-site travel cannot start for a remote booking.
        assertThrows(InvalidBookingTransitionException.class,
                () -> machine.markOnTheWay(booking(ServiceMode.REMOTE, BookingStatus.TECHNICIAN_ACCEPTED)));
        // Physical arrival cannot be marked for a remote booking.
        assertThrows(InvalidBookingTransitionException.class,
                () -> machine.markArrived(booking(ServiceMode.REMOTE, BookingStatus.TECHNICIAN_ON_THE_WAY)));
    }

    // -------------------------------------------------------------- terminal

    @Test
    void terminalStatesRejectNormalOperations() {
        for (BookingStatus terminal : new BookingStatus[]{BookingStatus.CANCELLED, BookingStatus.BOOKING_CLOSED}) {
            assertThrows(InvalidBookingTransitionException.class,
                    () -> machine.assignTechnician(booking(ServiceMode.ONSITE, terminal)));
            assertThrows(InvalidBookingTransitionException.class,
                    () -> machine.technicianAccept(booking(ServiceMode.REMOTE, terminal)));
            assertThrows(InvalidBookingTransitionException.class,
                    () -> machine.markOnTheWay(booking(ServiceMode.ONSITE, terminal)));
            assertThrows(InvalidBookingTransitionException.class,
                    () -> machine.startOnsiteService(booking(ServiceMode.ONSITE, terminal)));
            assertThrows(InvalidBookingTransitionException.class,
                    () -> machine.completeService(booking(ServiceMode.ONSITE, terminal)));
        }
        // A cancelled booking specifically cannot be closed.
        assertThrows(InvalidBookingTransitionException.class,
                () -> machine.closeBooking(booking(ServiceMode.ONSITE, BookingStatus.CANCELLED)));
    }

    // --------------------------------------------------------------- payment

    @Test
    void paymentConfirmedSetsCorrectBookingState() {
        Booking full = booking(ServiceMode.REMOTE, BookingStatus.PENDING);
        machine.onPaymentConfirmed(full, PaymentType.FULL);
        assertEquals(BookingStatus.PAYMENT_COMPLETED, full.getBookingStatus());

        Booking advance = booking(ServiceMode.ONSITE, BookingStatus.PENDING);
        machine.onPaymentConfirmed(advance, PaymentType.ADVANCE);
        assertEquals(BookingStatus.ASSIGNMENT_PENDING, advance.getBookingStatus());

        Booking remaining = booking(ServiceMode.ONSITE, BookingStatus.REMAINING_PAYMENT_PENDING);
        machine.onPaymentConfirmed(remaining, PaymentType.REMAINING);
        assertEquals(BookingStatus.SERVICE_COMPLETED, remaining.getBookingStatus());
    }

    // ------------------------------------------------------------- idempotency

    @Test
    void repeatedActionsDoNotDuplicateTimestamps() {
        Booking booking = booking(ServiceMode.ONSITE, BookingStatus.TECHNICIAN_ACCEPTED);

        assertTrue(machine.markOnTheWay(booking));
        LocalDateTime first = booking.getTechnicianOnTheWayAt();
        assertFalse(machine.markOnTheWay(booking));
        assertEquals(first, booking.getTechnicianOnTheWayAt(), "repeat must not rewrite the transition timestamp");
    }

    @Test
    void acceptOnTheWayCompleteAndCloseAreIdempotent() {
        Booking accepted = booking(ServiceMode.ONSITE, BookingStatus.TECHNICIAN_ACCEPTED);
        accepted.setTechnicianAcceptedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
        assertFalse(machine.technicianAccept(accepted));
        assertEquals(LocalDateTime.of(2026, 1, 1, 10, 0), accepted.getTechnicianAcceptedAt());

        Booking completed = booking(ServiceMode.ONSITE, BookingStatus.SERVICE_COMPLETED);
        assertFalse(machine.completeService(completed));

        Booking closed = booking(ServiceMode.ONSITE, BookingStatus.BOOKING_CLOSED);
        assertFalse(machine.closeBooking(closed));
    }

    @Test
    void closeRequiresCompletedServiceState() {
        assertThrows(InvalidBookingTransitionException.class,
                () -> machine.closeBooking(booking(ServiceMode.ONSITE, BookingStatus.PAYMENT_COMPLETED)));
        assertThrows(InvalidBookingTransitionException.class,
                () -> machine.closeBooking(booking(ServiceMode.ONSITE, BookingStatus.SERVICE_STARTED)));
        assertTrue(machine.closeBooking(booking(ServiceMode.ONSITE, BookingStatus.SERVICE_COMPLETED)));
    }

    @Test
    void remainingPaymentPendingRequiresServiceCompleted() {
        assertTrue(machine.markRemainingPaymentPending(booking(ServiceMode.ONSITE, BookingStatus.SERVICE_COMPLETED)));
        assertThrows(InvalidBookingTransitionException.class,
                () -> machine.markRemainingPaymentPending(booking(ServiceMode.ONSITE, BookingStatus.ASSIGNMENT_PENDING)));
    }

    // -------------------------------------------------------------- tracking

    @Test
    void trackingAllowedOnlyInActiveStates() {
        machine.assertTrackingAllowed(booking(ServiceMode.ONSITE, BookingStatus.TECHNICIAN_ON_THE_WAY));
        machine.assertTrackingAllowed(booking(ServiceMode.ONSITE, BookingStatus.TECHNICIAN_ASSIGNED));
        machine.assertTrackingAllowed(booking(ServiceMode.ONSITE, BookingStatus.TECHNICIAN_ARRIVED));

        assertThrows(InvalidBookingTransitionException.class,
                () -> machine.assertTrackingAllowed(booking(ServiceMode.ONSITE, BookingStatus.SERVICE_COMPLETED)));
        assertThrows(InvalidBookingTransitionException.class,
                () -> machine.assertTrackingAllowed(booking(ServiceMode.ONSITE, BookingStatus.BOOKING_CLOSED)));
        assertThrows(InvalidBookingTransitionException.class,
                () -> machine.assertTrackingAllowed(booking(ServiceMode.ONSITE, BookingStatus.CANCELLED)));
        assertThrows(InvalidBookingTransitionException.class,
                () -> machine.assertTrackingAllowed(booking(ServiceMode.ONSITE, null)));
    }

    @Test
    void assignmentRejectsCompletedAndCancelledStates() {
        assertThrows(InvalidBookingTransitionException.class,
                () -> machine.assignTechnician(booking(ServiceMode.REMOTE, BookingStatus.SERVICE_COMPLETED)));
        assertThrows(InvalidBookingTransitionException.class,
                () -> machine.assignTechnician(booking(ServiceMode.REMOTE, BookingStatus.CANCELLED)));
        machine.assignTechnician(booking(ServiceMode.REMOTE, BookingStatus.PAYMENT_COMPLETED));
        machine.assignTechnician(booking(ServiceMode.REMOTE, BookingStatus.TECHNICIAN_ASSIGNED));
    }

    // ---------------------------------------------------------------- helpers

    private int transitionError(Booking booking, java.util.function.Consumer<Booking> action) {
        InvalidBookingTransitionException exception =
                assertThrows(InvalidBookingTransitionException.class, () -> action.accept(booking));
        return exception.getStatusCode().value();
    }
}
