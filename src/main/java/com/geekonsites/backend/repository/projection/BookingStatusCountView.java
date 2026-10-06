package com.geekonsites.backend.repository.projection;

import com.geekonsites.backend.enums.BookingStatus;

public interface BookingStatusCountView {
    BookingStatus getStatus();
    long getTotal();
}
