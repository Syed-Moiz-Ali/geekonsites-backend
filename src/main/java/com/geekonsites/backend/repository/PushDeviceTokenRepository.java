package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.PushDeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PushDeviceTokenRepository extends JpaRepository<PushDeviceToken, Long> {
    Optional<PushDeviceToken> findByToken(String token);
    List<PushDeviceToken> findByCustomerIdAndActiveTrue(Long customerId);
    List<PushDeviceToken> findByCustomerIdAndRecipientRoleAndActiveTrue(Long customerId, String recipientRole);
}
