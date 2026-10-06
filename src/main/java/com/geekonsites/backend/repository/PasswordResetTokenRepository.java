package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.PasswordResetToken;
import com.geekonsites.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByTokenHashAndUsedFalse(String tokenHash);
    Optional<PasswordResetToken> findFirstByUserOrderByCreatedAtDesc(User user);
    void deleteAllByUser(User user);
}
