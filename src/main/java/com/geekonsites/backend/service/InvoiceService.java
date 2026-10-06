package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.Invoice;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.InvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final BookingRepository bookingRepository;

    public InvoiceService(
            InvoiceRepository invoiceRepository,
            BookingRepository bookingRepository
    ) {
        this.invoiceRepository = invoiceRepository;
        this.bookingRepository = bookingRepository;
    }

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
        invoice.setAmount(booking.getTotalAmount());
        invoice.setCurrency(booking.getCurrency());
        invoice.setPaymentStatus(booking.getPaymentStatus());
        invoice.setPaymentMethod(booking.getPaymentMethod());
        invoice.setPaymentTransactionId(booking.getPaymentTransactionId());
        invoice.setPaidAmount(booking.getPaidAmount());

        Invoice savedInvoice = invoiceRepository.save(invoice);

        booking.setInvoiceNumber(savedInvoice.getInvoiceNumber());
        booking.setInvoiceGenerated(true);
        booking.setInvoiceGeneratedAt(java.time.LocalDateTime.now());
        bookingRepository.save(booking);

        return savedInvoice;
    }

    private Invoice updateInvoice(Invoice invoice, Booking booking) {
        invoice.setTechnicianId(booking.getTechnicianId());
        invoice.setTechnicianName(booking.getTechnicianName());
        invoice.setAmount(booking.getTotalAmount());
        invoice.setPaymentStatus(booking.getPaymentStatus());
        invoice.setPaymentMethod(booking.getPaymentMethod());
        invoice.setPaymentTransactionId(booking.getPaymentTransactionId());
        invoice.setPaidAmount(booking.getPaidAmount());
        Invoice savedInvoice = invoiceRepository.save(invoice);

        booking.setInvoiceNumber(savedInvoice.getInvoiceNumber());
        booking.setInvoiceGenerated(true);
        booking.setInvoiceGeneratedAt(java.time.LocalDateTime.now());
        bookingRepository.save(booking);
        return savedInvoice;
    }

    private String generateInvoiceNumber(Booking booking) {
        String countryCode = "GOS";

        if ("UK".equalsIgnoreCase(booking.getCountry())) {
            countryCode = "GOS-UK";
        } else if ("US".equalsIgnoreCase(booking.getCountry())) {
            countryCode = "GOS-US";
        }

        return countryCode + "-INV-" + booking.getId();
    }
}
