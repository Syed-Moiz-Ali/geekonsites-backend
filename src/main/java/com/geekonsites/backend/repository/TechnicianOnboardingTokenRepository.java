package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.TechnicianOnboardingToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TechnicianOnboardingTokenRepository extends JpaRepository<TechnicianOnboardingToken, Long> {
    Optional<TechnicianOnboardingToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update TechnicianOnboardingToken t set t.used = true where t.technicianId = :technicianId and t.used = false")
    int invalidateActiveTokens(@Param("technicianId") Long technicianId);
}
