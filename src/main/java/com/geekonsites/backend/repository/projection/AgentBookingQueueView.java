package com.geekonsites.backend.repository.projection;

import com.geekonsites.backend.enums.BookingStatus;
import com.geekonsites.backend.enums.ServiceMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

public interface AgentBookingQueueView {
    Long getId();
    String getCustomerName();
    String getCustomerEmail();
    String getCustomerPhone();
    String getCountry();
    String getCity();
    String getState();
    String getAddress();
    String getServiceType();
    ServiceMode getServiceMode();
    String getPaymentStatus();
    Long getTechnicianId();
    String getTechnicianName();
    BookingStatus getBookingStatus();
    LocalDateTime getCreatedAt();
    LocalDate getBookingDate();
    String getTimeSlot();
}
