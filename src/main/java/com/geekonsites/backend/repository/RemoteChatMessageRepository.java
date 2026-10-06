package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.RemoteChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RemoteChatMessageRepository extends JpaRepository<RemoteChatMessage, Long> {
    List<RemoteChatMessage> findByBookingIdOrderByCreatedAtAscIdAsc(Long bookingId);
}
