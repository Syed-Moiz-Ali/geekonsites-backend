package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InvoiceRepository
        extends JpaRepository<Invoice, Long> {

    Optional<Invoice> findFirstByBookingIdOrderByIdAsc(Long bookingId);
}
