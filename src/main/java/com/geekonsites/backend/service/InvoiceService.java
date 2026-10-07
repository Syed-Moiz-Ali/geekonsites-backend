package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Invoice;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.InvoiceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;

/**
 * PHASE 9 — the single invoice-generation authority.
 *
 * <p>Every caller (PaymentService, BookingService, InvoiceController, the booking
 * generate-invoice endpoint) delegates here. There is exactly one numbering scheme, one
 * idempotency rule (one Invoice per {@code bookingId}) and one concurrency guard.
 */
@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final BookingRepository bookingRepository;
    private final Clock clock;

    public InvoiceService(
            InvoiceRepository invoiceRepository,
            BookingRepository bookingRepository,
            Clock clock
    ) {
        this.invoiceRepository = invoiceRepository;
        this.bookingRepository = bookingRepository;
        this.clock = clock;
    }

    @Transactional
    public synchronized Invoice generateInvoiceFromBooking(Long bookingId) {

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));

        var existingInvoice = invoiceRepository.findFirstByBookingIdOrderByIdAsc(bookingId);
        if (existingInvoice.isPresent() && "BALANCE_PENDING".equalsIgnoreCase(booking.getPaymentStatus())) {
            return existingInvoice.get();
        }

        if (!"PAID".equalsIgnoreCase(booking.getPaymentStatus())
                && !"PARTIALLY_PAID".equalsIgnoreCase(booking.getPaymentStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invoice is available after payment is confirmed");
        }

        return existingInvoice
                .map(existing -> updateInvoice(existing, booking))
                .orElseGet(() -> createInvoice(booking));
    }

    public Invoice getInvoiceById(Long invoiceId) {
        return invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
    }

    public Invoice getInvoiceByBookingId(Long bookingId) {
        return invoiceRepository.findFirstByBookingIdOrderByIdAsc(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
    }

    private Invoice createInvoice(Booking booking) {

        Invoice invoice = new Invoice();

        invoice.setInvoiceNumber(generateInvoiceNumber(booking));
        invoice.setBookingId(booking.getId());
        invoice.setCustomerId(booking.getCustomerId());
        invoice.setTechnicianId(booking.getTechnicianId());
        invoice.setCustomerName(booking.getCustomerName());
        invoice.setTechnicianName(booking.getTechnicianName());
        invoice.setServiceType(booking.getServiceType());
        applyExactAmounts(invoice, booking);
        invoice.setCurrency(booking.getCurrency());
        invoice.setPaymentStatus(booking.getPaymentStatus());
        invoice.setPaymentMethod(booking.getPaymentMethod());
        invoice.setPaymentTransactionId(booking.getPaymentTransactionId());

        Invoice savedInvoice;
        try {
            savedInvoice = invoiceRepository.saveAndFlush(invoice);
        } catch (DataIntegrityViolationException duplicate) {
            // PHASE 4: concurrent generation lost the race to the unique booking_id index;
            // return the winning invoice rather than creating a duplicate.
            return invoiceRepository.findFirstByBookingIdOrderByIdAsc(booking.getId()).orElseThrow(() -> duplicate);
        }

        booking.setInvoiceNumber(savedInvoice.getInvoiceNumber());
        booking.setInvoiceGenerated(true);
        booking.setInvoiceGeneratedAt(java.time.LocalDateTime.now());
        bookingRepository.save(booking);

        return savedInvoice;
    }

    private Invoice updateInvoice(Invoice invoice, Booking booking) {
        invoice.setTechnicianId(booking.getTechnicianId());
        invoice.setTechnicianName(booking.getTechnicianName());
        applyExactAmounts(invoice, booking);
        invoice.setPaymentStatus(booking.getPaymentStatus());
        invoice.setPaymentMethod(booking.getPaymentMethod());
        invoice.setPaymentTransactionId(booking.getPaymentTransactionId());
        Invoice savedInvoice = invoiceRepository.save(invoice);

        booking.setInvoiceNumber(savedInvoice.getInvoiceNumber());
        booking.setInvoiceGenerated(true);
        booking.setInvoiceGeneratedAt(java.time.LocalDateTime.now());
        bookingRepository.save(booking);
        return savedInvoice;
    }

    /**
     * PHASE 8 — copies the booking's exact minor-unit money onto the invoice. The
     * invoice Double columns are deprecated mirrors derived from the exact values.
     */
    private void applyExactAmounts(Invoice invoice, Booking booking) {
        long amountMinor = PaymentMoney.resolveMinor(booking.getTotalAmountMinor(), booking.getTotalAmount());
        long paidMinor = PaymentMoney.resolveMinor(booking.getPaidAmountMinor(), booking.getPaidAmount());
        invoice.setAmountMinor(amountMinor);
        invoice.setPaidAmountMinor(paidMinor);
        invoice.setAmount(PaymentMoney.toMajor(amountMinor));
        invoice.setPaidAmount(PaymentMoney.toMajor(paidMinor));
    }

    /**
     * PHASE 9 — the single invoice-number format: {@code GOS-{market}-{year}-{bookingId}}.
     * Deterministic, support-friendly, unique (bookingId is unique) and concurrency-safe
     * (no count()+1). The year is derived from the injected {@link Clock}, never hardcoded.
     */
    private String generateInvoiceNumber(Booking booking) {
        int year = LocalDate.now(clock).getYear();
        if ("UK".equalsIgnoreCase(booking.getCountry())) {
            return "GOS-UK-" + year + "-" + booking.getId();
        }
        if ("US".equalsIgnoreCase(booking.getCountry())) {
            return "GOS-US-" + year + "-" + booking.getId();
        }
        return "GOS-" + year + "-" + booking.getId();
    }
}
