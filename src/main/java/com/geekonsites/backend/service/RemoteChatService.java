package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Booking;
import com.geekonsites.backend.entity.RemoteChatMessage;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.repository.BookingRepository;
import com.geekonsites.backend.repository.RemoteChatMessageRepository;
import com.geekonsites.backend.repository.TechnicianRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class RemoteChatService {
    private final BookingRepository bookingRepository;
    private final RemoteChatMessageRepository messageRepository;
    private final TechnicianRepository technicianRepository;

    public RemoteChatService(BookingRepository bookingRepository,
                             RemoteChatMessageRepository messageRepository,
                             TechnicianRepository technicianRepository) {
        this.bookingRepository = bookingRepository;
        this.messageRepository = messageRepository;
        this.technicianRepository = technicianRepository;
    }

    public List<RemoteChatMessage> history(Long bookingId, User sender) {
        authorizePaidParticipant(bookingId, sender);
        return messageRepository.findByBookingIdOrderByCreatedAtAscIdAsc(bookingId);
    }

    public RemoteChatMessage send(Long bookingId, String text, User sender) {
        authorizePaidParticipant(bookingId, sender);
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message cannot be empty");
        if (clean.length() > 2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message is too long");

        RemoteChatMessage message = new RemoteChatMessage();
        message.setBookingId(bookingId);
        message.setSenderUserId(sender.getId());
        message.setSenderRole(sender.getRole().name());
        message.setMessage(clean);
        return messageRepository.save(message);
    }

    Booking authorizePaidParticipant(Long bookingId, User sender) {
        if (sender == null || sender.getRole() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
        if (booking.getServiceMode() != ServiceMode.REMOTE || !"PAID".equalsIgnoreCase(booking.getPaymentStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Paid remote booking required");
        }

        boolean allowed = false;
        if (sender.getRole() == Role.CUSTOMER) allowed = sender.getId().equals(booking.getCustomerId());
        if (sender.getRole() == Role.TECHNICIAN) {
            var technician = technicianRepository.findAccessByEmail(sender.getEmail())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Technician profile not found"));
            allowed = technician.getId().equals(booking.getTechnicianId());
        }
        if (sender.getRole() == Role.ADMIN || sender.getRole() == Role.AGENT) allowed = true;
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot access this booking chat");
        return booking;
    }
}
