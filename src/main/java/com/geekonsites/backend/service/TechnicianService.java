package com.geekonsites.backend.service;

import com.geekonsites.backend.dto.TechnicianRequest;
import com.geekonsites.backend.dto.TechnicianRegistrationResponse;
import com.geekonsites.backend.dto.TechnicianAdminResponse;
import com.geekonsites.backend.dto.TechnicianSetPasswordRequest;
import com.geekonsites.backend.dto.TechnicianSetPasswordResponse;
import com.geekonsites.backend.entity.Technician;
import com.geekonsites.backend.entity.TechnicianOnboardingToken;
import com.geekonsites.backend.entity.User;
import com.geekonsites.backend.enums.Role;
import com.geekonsites.backend.enums.TechnicianOnboardingStatus;
import com.geekonsites.backend.repository.TechnicianRepository;
import com.geekonsites.backend.repository.TechnicianOnboardingTokenRepository;
import com.geekonsites.backend.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
public class TechnicianService {

    private static final Logger log = LoggerFactory.getLogger(TechnicianService.class);
    private static final long MAX_EVIDENCE_BYTES = 5L * 1024L * 1024L;
    private static final String REGISTRATION_SUCCESS_MESSAGE =
            "Status: HR Review Pending. After approval, sign in with this personal email and your registration password.";
    private static final Duration ONBOARDING_TOKEN_LIFETIME = Duration.ofHours(24);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final TechnicianRepository technicianRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TechnicianOnboardingTokenRepository onboardingTokenRepository;
    private final ApplicationEventPublisher eventPublisher;

    public TechnicianService(
            TechnicianRepository technicianRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TechnicianOnboardingTokenRepository onboardingTokenRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.technicianRepository = technicianRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.onboardingTokenRepository = onboardingTokenRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public TechnicianRegistrationResponse createTechnician(TechnicianRequest request) {
        String email = request.getEmail() == null ? "" : request.getEmail().trim().toLowerCase();
        if (email.isBlank() || request.getPassword() == null || request.getPassword().length() < 8) {
            throw new RuntimeException("A valid email and password are required");
        }
        if (technicianRepository.existsByEmailIgnoreCase(email) || userRepository.existsByEmailIgnoreCase(email)) {
            throw new RuntimeException("An account already exists for this email");
        }
        if (request.getCitizenshipStatus() == null || request.getIdentityDocumentType() == null ||
                request.getIdentityDocumentData() == null || request.getLivePhotoData() == null) {
            throw new RuntimeException("Identity document and live photo verification are required");
        }
        if ("FOREIGN_NATIONAL".equals(request.getCitizenshipStatus()) &&
                !"PASSPORT".equals(request.getIdentityDocumentType())) {
            throw new RuntimeException("Foreign technicians must provide a passport");
        }
        validateEvidence("identity document", request.getIdentityDocumentData());
        validateEvidence("live photo", request.getLivePhotoData());
        validateOptionalEvidence("work authorization document", request.getWorkAuthorizationDocumentData());
        validateEvidence("address proof", request.getAddressProofData());
        validateOptionalEvidence("driving license", request.getDrivingLicenseData());
        validateOptionalEvidence("vehicle insurance", request.getVehicleInsuranceData());
        validateOptionalEvidence("public liability document", request.getPublicLiabilityData());

        boolean onsite = !"REMOTE_ONLY".equals(request.getServiceMode());
        if (request.getWorkAuthorizationType() == null || request.getAddressProofData() == null ||
                request.getAddressHistory() == null || request.getAddressHistory().isBlank() ||
                ("FOREIGN_NATIONAL".equals(request.getCitizenshipStatus()) && request.getWorkAuthorizationDocumentData() == null) ||
                (onsite && (request.getDrivingLicenseData() == null || request.getVehicleInsuranceData() == null || request.getPublicLiabilityData() == null))) {
            throw new RuntimeException("Required compliance evidence is incomplete");
        }

        Technician technician = new Technician();

        technician.setName(request.getName());
        technician.setEmail(email);
        technician.setPersonalEmail(email);
        technician.setPhone(request.getPhone());
        technician.setCountry(request.getCountry());
        technician.setCity(request.getCity());
        technician.setSpecialization(request.getSpecialization());
        technician.setExperienceYears(request.getExperienceYears());
        technician.setCitizenshipStatus(request.getCitizenshipStatus());
        technician.setIdentityDocumentType(request.getIdentityDocumentType());
        technician.setIdentityDocumentName(request.getIdentityDocumentName());
        technician.setIdentityDocumentData(request.getIdentityDocumentData());
        technician.setLivePhotoData(request.getLivePhotoData());
        technician.setEmploymentType(request.getEmploymentType());
        technician.setServiceMode(request.getServiceMode());
        technician.setWorkAuthorizationType(request.getWorkAuthorizationType());
        technician.setWorkAuthorizationExpiry(request.getWorkAuthorizationExpiry());
        technician.setWorkAuthorizationDocumentName(request.getWorkAuthorizationDocumentName());
        technician.setWorkAuthorizationDocumentData(request.getWorkAuthorizationDocumentData());
        technician.setAddressHistory(request.getAddressHistory());
        technician.setAddressProofName(request.getAddressProofName());
        technician.setAddressProofData(request.getAddressProofData());
        technician.setDrivingLicenseName(request.getDrivingLicenseName());
        technician.setDrivingLicenseData(request.getDrivingLicenseData());
        technician.setVehicleInsuranceName(request.getVehicleInsuranceName());
        technician.setVehicleInsuranceData(request.getVehicleInsuranceData());
        technician.setPublicLiabilityName(request.getPublicLiabilityName());
        technician.setPublicLiabilityData(request.getPublicLiabilityData());

        technician.setAvailabilityStatus("UNAVAILABLE");
        technician.setVerificationStatus("PENDING");
        technician.setOnboardingStatus(TechnicianOnboardingStatus.NOT_STARTED);
        technician.setRating(0.0);

        long hashStartedAt = System.nanoTime();
        String encodedPassword = passwordEncoder.encode(request.getPassword());
        long hashDurationMs = elapsedMillis(hashStartedAt);

        User user = new User();
        user.setFullName(request.getName());
        user.setEmail(email);
        user.setPassword(encodedPassword);
        user.setPhone(request.getPhone());
        user.setCountry("UK".equalsIgnoreCase(request.getCountry()) ? "UK" : "US");
        user.setRole(Role.TECHNICIAN);

        long databaseStartedAt = System.nanoTime();
        Technician savedTechnician = technicianRepository.save(technician);
        userRepository.saveAndFlush(user);
        long databaseDurationMs = elapsedMillis(databaseStartedAt);

        log.info("Technician registration persistence performance passwordHashMs={} databaseSaveMs={}",
                hashDurationMs, databaseDurationMs);

        return new TechnicianRegistrationResponse(
                savedTechnician.getId(),
                savedTechnician.getName(),
                savedTechnician.getEmail(),
                savedTechnician.getVerificationStatus(),
                savedTechnician.getServiceMode(),
                REGISTRATION_SUCCESS_MESSAGE
        );
    }

    private void validateOptionalEvidence(String label, String dataUrl) {
        if (dataUrl != null && !dataUrl.isBlank()) {
            validateEvidence(label, dataUrl);
        }
    }

    private void validateEvidence(String label, String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) {
            throw new RuntimeException(label + " is required");
        }
        int commaIndex = dataUrl.indexOf(',');
        if (commaIndex < 0 || !dataUrl.startsWith("data:") ||
                !(dataUrl.startsWith("data:image/jpeg;base64,") ||
                        dataUrl.startsWith("data:image/png;base64,") ||
                        dataUrl.startsWith("data:application/pdf;base64,"))) {
            throw new RuntimeException(label + " must be a JPG, PNG, or PDF file");
        }
        long base64Length = dataUrl.length() - commaIndex - 1L;
        long padding = dataUrl.endsWith("==") ? 2L : dataUrl.endsWith("=") ? 1L : 0L;
        long decodedBytes = (base64Length * 3L) / 4L - padding;
        if (decodedBytes > MAX_EVIDENCE_BYTES) {
            throw new RuntimeException(label + " must be 5 MB or smaller");
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    public List<Technician> getAllTechnicians() {
        return technicianRepository.findAll();
    }

    public Technician getTechnicianById(Long id) {
        return technicianRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Technician not found"));
    }

    public List<Technician> getPendingTechnicians() {
        return technicianRepository.findByVerificationStatus("PENDING");
    }

    @Transactional
    public TechnicianAdminResponse approveTechnician(Long id) {
        Technician technician = getTechnicianById(id);

        if ("APPROVED".equalsIgnoreCase(technician.getVerificationStatus())) {
            return TechnicianAdminResponse.from(technician);
        }

        boolean onsite = !"REMOTE_ONLY".equals(technician.getServiceMode());
        if (technician.getIdentityDocumentData() == null || technician.getLivePhotoData() == null ||
                technician.getWorkAuthorizationType() == null || technician.getAddressProofData() == null ||
                ("FOREIGN_NATIONAL".equals(technician.getCitizenshipStatus()) && technician.getWorkAuthorizationDocumentData() == null) ||
                (onsite && (technician.getDrivingLicenseData() == null || technician.getVehicleInsuranceData() == null || technician.getPublicLiabilityData() == null))) {
            throw new RuntimeException("Technician verification evidence is incomplete");
        }

        // Technicians sign in with the personal email and password they created
        // at registration - approval never issues a separate @gos.com account
        // or a new password, so nothing changes about how they log in.
        String personalEmail = technician.getPersonalEmail() == null ? technician.getEmail() : technician.getPersonalEmail();
        technician.setPersonalEmail(personalEmail);
        technician.setEmail(personalEmail);
        User user = userRepository.findByEmailIgnoreCase(personalEmail)
                .orElseThrow(() -> new RuntimeException("Technician login account is missing"));
        user.setEmail(personalEmail);
        userRepository.save(user);

        technician.setVerificationStatus("APPROVED");
        technician.setAvailabilityStatus("AVAILABLE");
        Technician saved = technicianRepository.save(technician);
        eventPublisher.publishEvent(new TechnicianApprovalEmailEvent(saved.getId(), personalEmail, saved.getName()));

        return TechnicianAdminResponse.from(saved);
    }

    @Transactional
    public TechnicianAdminResponse resendOnboarding(Long id) {
        Technician technician = getTechnicianById(id);
        if (!"APPROVED".equalsIgnoreCase(technician.getVerificationStatus())) {
            throw new RuntimeException("Only approved technicians can receive an approval notification");
        }
        String personalEmail = technician.getPersonalEmail();
        if (personalEmail == null || personalEmail.isBlank()) {
            throw new RuntimeException("Technician personal email is missing");
        }
        eventPublisher.publishEvent(new TechnicianApprovalEmailEvent(technician.getId(), personalEmail, technician.getName()));
        return TechnicianAdminResponse.from(technician);
    }

    @Transactional
    public TechnicianSetPasswordResponse setOnboardingPassword(TechnicianSetPasswordRequest request) {
        if (request == null || request.token() == null || request.token().isBlank()) {
            throw new IllegalArgumentException("Password setup link is invalid");
        }
        if (!isStrongPassword(request.password())) {
            throw new IllegalArgumentException("Password must be 8 to 72 characters with uppercase, lowercase, number, and special character");
        }
        TechnicianOnboardingToken token = onboardingTokenRepository.findByTokenHash(hash(request.token()))
                .orElseThrow(() -> new IllegalArgumentException("Password setup link is invalid"));
        if (token.isUsed()) throw new IllegalArgumentException("Password setup link has already been used");
        if (!technicianRepository.existsByIdAndVerificationStatus(token.getTechnicianId(), "APPROVED")) {
            throw new IllegalArgumentException("Technician account is not eligible for password setup");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            token.setUsed(true);
            onboardingTokenRepository.save(token);
            throw new IllegalArgumentException("Password setup link has expired");
        }

        User user = token.getUser();
        user.setPassword(passwordEncoder.encode(request.password()));
        userRepository.save(user);
        token.setUsed(true);
        onboardingTokenRepository.save(token);
        onboardingTokenRepository.invalidateActiveTokens(token.getTechnicianId());
        technicianRepository.markPasswordSetupComplete(token.getTechnicianId(), Instant.now());
        return new TechnicianSetPasswordResponse(user.getEmail(), "Your technician account is ready.");
    }

    private void publishNewOnboardingToken(Technician technician, User user) {
        onboardingTokenRepository.invalidateActiveTokens(technician.getId());
        String rawToken = generateRawToken();
        TechnicianOnboardingToken token = new TechnicianOnboardingToken();
        token.setTechnicianId(technician.getId());
        token.setUser(user);
        token.setTokenHash(hash(rawToken));
        token.setExpiresAt(Instant.now().plus(ONBOARDING_TOKEN_LIFETIME));
        onboardingTokenRepository.save(token);
        eventPublisher.publishEvent(new TechnicianOnboardingEmailEvent(
                technician.getId(), technician.getPersonalEmail(), technician.getName(), technician.getCompanyEmail(), rawToken
        ));
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean isStrongPassword(String password) {
        return password != null && password.length() >= 8 && password.length() <= 72
                && password.matches(".*[A-Z].*") && password.matches(".*[a-z].*")
                && password.matches(".*[0-9].*") && password.matches(".*[^A-Za-z0-9].*");
    }

    @Transactional
    public Technician rejectTechnician(Long id) {
        Technician technician = getTechnicianById(id);

        technician.setVerificationStatus("REJECTED");
        technician.setAvailabilityStatus("UNAVAILABLE");
        technician.setOnboardingStatus(TechnicianOnboardingStatus.NOT_STARTED);
        onboardingTokenRepository.invalidateActiveTokens(id);

        return technicianRepository.save(technician);
    }

    public Technician updateAvailability(Long id, String status) {
        Technician technician = getTechnicianById(id);
        if (!"APPROVED".equalsIgnoreCase(technician.getVerificationStatus())) {
            throw new RuntimeException("Only approved technicians can change availability");
        }
        if (!"AVAILABLE".equalsIgnoreCase(status) &&
                !"BUSY".equalsIgnoreCase(status) &&
                !"UNAVAILABLE".equalsIgnoreCase(status)) {
            throw new RuntimeException("Availability must be AVAILABLE, BUSY, or UNAVAILABLE");
        }
        technician.setAvailabilityStatus(status.toUpperCase());
        return technicianRepository.save(technician);
    }
}
