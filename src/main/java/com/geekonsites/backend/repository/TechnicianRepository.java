package com.geekonsites.backend.repository;

import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.repository.projection.TechnicianAccessView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.Optional;

public interface TechnicianRepository extends JpaRepository<Technician, Long> {

    List<Technician> findByVerificationStatus(String verificationStatus);

    Optional<Technician> findByEmail(String email);

    // This resolves the email that actually authenticated at the users
    // table (the JWT subject / login email) back to the owning technician,
    // so the approval/onboarding gates in AuthController.login can be
    // applied. It is NOT what decides a technician's login identity - that
    // is entirely controlled by users.email, which only registration,
    // approval, and the personal-email backfill migration ever write to.
    //
    // Authentication and JWT subjects are canonical personal emails. Legacy
    // company/technician email columns remain metadata and are deliberately
    // excluded from this access lookup.
    @Query("""
            select t.id as id,
                   t.email as email,
                   t.personalEmail as personalEmail,
                   t.companyEmail as companyEmail,
                   t.verificationStatus as verificationStatus,
                   t.onboardingStatus as onboardingStatus,
                   t.availabilityStatus as availabilityStatus,
                   t.serviceMode as serviceMode
            from Technician t
            where lower(:email) = lower(t.personalEmail)
            """)
    Optional<TechnicianAccessView> findAccessByEmail(@Param("email") String email);

    Optional<Technician> findByPhone(String phone);

    boolean existsByEmail(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByCompanyEmailIgnoreCase(String companyEmail);

    @Query("select t.personalEmail from Technician t where lower(t.companyEmail) = lower(:companyEmail)")
    Optional<String> findPersonalEmailByCompanyEmail(@Param("companyEmail") String companyEmail);

    @Modifying
    @Query("""
            update Technician t set t.onboardingStatus = :status,
                t.onboardingEmailSentAt = :sentAt
            where t.id = :technicianId
              and t.verificationStatus = 'APPROVED'
              and t.onboardingStatus <> com.geekonsites.backend.enums.TechnicianOnboardingStatus.PASSWORD_SET
            """)
    int updateOnboardingDeliveryStatus(
            @Param("technicianId") Long technicianId,
            @Param("status") com.geekonsites.backend.enums.TechnicianOnboardingStatus status,
            @Param("sentAt") java.time.Instant sentAt
    );

    @Modifying
    @Query("""
            update Technician t set t.onboardingStatus = com.geekonsites.backend.enums.TechnicianOnboardingStatus.PASSWORD_SET,
                t.passwordSetupCompletedAt = :completedAt
            where t.id = :technicianId
            """)
    int markPasswordSetupComplete(@Param("technicianId") Long technicianId, @Param("completedAt") java.time.Instant completedAt);

    boolean existsByIdAndVerificationStatus(Long id, String verificationStatus);

    boolean existsByPhone(String phone);
    long countByVerificationStatusIgnoreCaseAndAvailabilityStatusIgnoreCase(String verificationStatus, String availabilityStatus);
    long countByVerificationStatusIgnoreCase(String verificationStatus);
    long countByVerificationStatusIgnoreCaseAndAvailabilityStatusIgnoreCaseNot(String verificationStatus, String availabilityStatus);
}
