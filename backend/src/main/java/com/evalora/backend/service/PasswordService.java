package com.evalora.backend.service;

import com.evalora.backend.dto.*;
import com.evalora.backend.entity.*;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.repository.*;
import com.evalora.backend.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    /** OTP tokens are valid for ten minutes. */
    static final Duration OTP_LIFETIME = Duration.ofMinutes(10);

    /** Maximum number of OTP requests in the rate-limit window. */
    static final int MAX_OTP_REQUESTS = 5;

    /** Maximum incorrect submissions for one OTP. */
    static final int MAX_OTP_VERIFICATION_ATTEMPTS = 5;

    /** Resend cooldown for OTP, in seconds. */
    static final int RESEND_COOLDOWN_SECONDS = 60;

    /** Pepper for OTP HMAC hashing - from environment. */
    private final String otpPepper;

    /** Configured maximum OTP requests per email per 15-minute window. */
    private final int maxOtpRequests;

    /** Configured resend cooldown, in seconds. Zero disables the cooldown. */
    private final int resendCooldownSeconds;

    /** In-memory rate limiter for OTP requests (per email). */
    private final Map<String, OtpRateLimit> otpRateLimitMap = new ConcurrentHashMap<>();

    /** Cached OTP entries keyed by hashed OTP (for verification). */
    private final Map<String, OtpRecord> otpCache = new ConcurrentHashMap<>();

    /** Reset tokens are valid for fifteen minutes. */
    static final Duration RESET_TOKEN_LIFETIME = Duration.ofMinutes(15);

    static final String FORGOT_PASSWORD_MESSAGE =
            "If an account with that email exists, password reset instructions have been made available.";
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final ActivationTokenRepository activationTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean devResetEnabled;
    private final String devResetEmail;
    private final EmailService emailService;

    public PasswordService(UserRepository userRepository,
                           EmployeeRepository employeeRepository,
                           PasswordResetTokenRepository tokenRepository,
                           ActivationTokenRepository activationTokenRepository,
                           PasswordEncoder passwordEncoder,
                           Environment environment,
                           EmailService emailService) {
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
        this.tokenRepository = tokenRepository;
        this.activationTokenRepository = activationTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.devResetEnabled = environment.acceptsProfiles(Profiles.of("dev"));
        this.devResetEmail = environment.getProperty("evalora.dev-reset.email", "");
        this.otpPepper = resolveOtpPepper(environment);
        this.maxOtpRequests = positiveOrDefault(
                environment.getProperty("evalora.otp.max-attempts", Integer.class),
                MAX_OTP_REQUESTS);
        this.resendCooldownSeconds = nonNegativeOrDefault(
                environment.getProperty("evalora.otp.resend-cooldown", Integer.class),
                RESEND_COOLDOWN_SECONDS);
        this.emailService = emailService;
    }

    // ------------------------------------------------------------ change password

    @Transactional
    public PasswordOperationResult changePassword(Long userId,
                                                  String currentPassword,
                                                  String newPassword,
                                                  String confirmPassword) {
        if (!Objects.equals(newPassword, confirmPassword)) {
            return PasswordOperationResult.CONFIRMATION_MISMATCH;
        }
        if (!PasswordPolicy.isValid(newPassword)) {
            return PasswordOperationResult.PASSWORD_POLICY_VIOLATION;
        }

        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null || !user.isEnabled()) {
            return PasswordOperationResult.CURRENT_PASSWORD_INCORRECT;
        }

        if (currentPassword == null
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            return PasswordOperationResult.CURRENT_PASSWORD_INCORRECT;
        }

        // Compare against the stored hash: BCrypt salts make plain equality unreliable.
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            return PasswordOperationResult.PASSWORD_REUSED;
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));

        // Invalidate all activation tokens and reset tokens for this user
        // so that a password change makes old tokens/stale links unusable.
        activationTokenRepository.deleteAllByUser_Id(user.getId());
        tokenRepository.invalidateAllUnusedForUser(user.getId(),
                OffsetDateTime.now(ZoneOffset.UTC));

        // Increment token version to invalidate JWTs
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);

        return PasswordOperationResult.SUCCESS;
    }

    // ------------------------------------------------------------ forgot password (OTP)

    @Transactional
    public ForgotPasswordResult forgotPassword(String rawEmail) {
        if (rawEmail == null) {
            return new ForgotPasswordResult(FORGOT_PASSWORD_MESSAGE);
        }

        String email = EmailNormalizer.normalize(rawEmail);
        User user = userRepository.findByEmailForUpdate(email).orElse(null);

        if (user == null || !user.isEnabled()) {
            // Return generic message for unknown, disabled, or existing accounts
            return new ForgotPasswordResult(FORGOT_PASSWORD_MESSAGE);
        }

        // Check rate limiting: maximum attempts per time window plus a resend
        // cooldown between accepted requests.
        OtpRateLimit rateLimit = otpRateLimitMap.computeIfAbsent(email,
                key -> new OtpRateLimit(maxOtpRequests, resendCooldownSeconds));
        if (!rateLimit.allowRequest()) {
            // Keep the response indistinguishable from unknown or disabled
            // accounts; only the registered address is rate-limited.
            return new ForgotPasswordResult(FORGOT_PASSWORD_MESSAGE);
        }

        // Invalidate any previous unused OTPs for this user
        invalidateAllUnusedOtpsForUser(user.getId());

        // Generate a 6-digit OTP
        String rawOtp = generateOtp();
        String otpHash = hashOtp(rawOtp);

        // Store the OTP hash with 10-minute expiry
        OtpRecord otpRecord = new OtpRecord();
        otpRecord.user = user;
        otpRecord.otpHash = otpHash;
        otpRecord.expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(OTP_LIFETIME);
        // Store in cache for quick lookup; persistence would use a dedicated table
        otpCache.put(email, otpRecord);

        try {
            sendOtp(user, rawOtp);
        } catch (EmailDeliveryException ex) {
            // The code never reached the user. Discard it so an undisclosed OTP
            // cannot be used, and release the cooldown so the user can retry
            // immediately while still counting against the request limit.
           otpCache.remove(email, otpRecord);
            rateLimit.markDeliveryFailed();
            log.warn("Password-reset OTP delivery failed ({}). The cached OTP was discarded; "
                            + "no OTP or provider details are logged.",
                    ex.getClass().getSimpleName());
            return new ForgotPasswordResult(FORGOT_PASSWORD_MESSAGE);
        }

        return new ForgotPasswordResult(FORGOT_PASSWORD_MESSAGE);
    }

    // ------------------------------------------------------------- verify OTP

    @Transactional
    public VerifyOtpResult verifyOtp(String rawEmail, String rawOtp) {
        if (rawEmail == null || rawOtp == null) {
            return VerifyOtpResult.invalid();
        }

        String email = EmailNormalizer.normalize(rawEmail);
        OtpRecord otpRecord = otpCache.get(email);
        if (otpRecord == null) {
            return VerifyOtpResult.invalid();
        }

        synchronized (otpRecord) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            if (otpRecord.usedAt != null
                    || otpRecord.expiresAt == null
                    || !otpRecord.expiresAt.isAfter(now)
                    || otpRecord.user == null
                    || !otpRecord.user.isEnabled()) {
                otpCache.remove(email, otpRecord);
                return VerifyOtpResult.invalid();
            }

            if (!otpRecord.otpHash.equals(hashOtp(rawOtp))) {
                otpRecord.failedAttempts++;
                if (otpRecord.failedAttempts >= MAX_OTP_VERIFICATION_ATTEMPTS) {
                    otpCache.remove(email, otpRecord);
                }
                return VerifyOtpResult.invalid();
            }

            String resetTicket = PasswordResetTokenUtil.generate();
            PasswordResetToken resetToken = new PasswordResetToken();
            resetToken.setUser(otpRecord.user);
            resetToken.setTokenHash(PasswordResetTokenUtil.hash(resetTicket));
            resetToken.setExpiresAt(now.plus(RESET_TOKEN_LIFETIME));
            tokenRepository.save(resetToken);
            otpRecord.usedAt = now;
            otpCache.remove(email, otpRecord);

            return VerifyOtpResult.valid(resetTicket);
        }
    }

    /** Clear, non-enumerating messages for every activation outcome. */
    static final String ACTIVATION_INVALID_MESSAGE =
            "This activation link is invalid or has already been used. "
                    + "Ask your HR administrator to resend the activation email.";
    static final String ACTIVATION_EXPIRED_MESSAGE =
            "This activation link has expired. "
                    + "Ask your HR administrator to resend the activation email.";
    static final String ACTIVATION_ALREADY_ACTIVE_MESSAGE =
            "This account is already active. Sign in with your password.";
    static final String ACTIVATION_PASSWORD_MISMATCH_MESSAGE =
            "New password and confirmation do not match.";
    static final String ACTIVATION_SUCCESS_MESSAGE = "Account activated successfully.";

    /**
     * Activate a new employee or manager account and set the employee's own
     * password in the same transaction.
     *
     * <p>The raw token is hashed by the caller; only the hash reaches this
     * method. The token must exist, be unexpired, and belong to a still-disabled
     * account. On success the password hash is replaced, the account is enabled,
     * and every activation token for the account is deleted, which makes the
     * link single-use.</p>
     *
     * <p>Invalid, expired, reused and already-active links return distinct
     * messages so the page can tell the employee what happened without
     * disclosing whether an account exists for any particular email address.</p>
     */
    @Transactional
    public ActivationResult activateAccount(String tokenHash,
                                            String newPassword,
                                            String confirmPassword) {
        if (!Objects.equals(newPassword, confirmPassword)) {
            return ActivationResult.failure(ACTIVATION_PASSWORD_MISMATCH_MESSAGE);
        }
        if (!PasswordPolicy.isValid(newPassword)) {
            return ActivationResult.failure(PasswordPolicy.MESSAGE);
        }
        if (tokenHash == null || tokenHash.isBlank()) {
            return ActivationResult.failure(ACTIVATION_INVALID_MESSAGE);
        }

        Long userId = activationTokenRepository.findUserIdByTokenHash(tokenHash).orElse(null);
        if (userId == null) {
            return ActivationResult.failure(ACTIVATION_INVALID_MESSAGE);
        }

        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null) {
            return ActivationResult.failure(ACTIVATION_INVALID_MESSAGE);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Optional<ActivationTokenEntity> tokenOpt = activationTokenRepository
                .findByTokenHashForUpdate(tokenHash);
        if (tokenOpt.isEmpty()) {
            // Deleted rows mean the link was already consumed or replaced by a
            // resend. The account id is never returned in the message.
            return ActivationResult.failure(ACTIVATION_INVALID_MESSAGE);
        }
        if (!tokenOpt.get().getExpiresAt().isAfter(now)) {
            return ActivationResult.failure(ACTIVATION_EXPIRED_MESSAGE);
        }
        if (user.isEnabled()) {
            return ActivationResult.failure(true, ACTIVATION_ALREADY_ACTIVE_MESSAGE);
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setEnabled(true);
        userRepository.save(user);
        // Single use: remove every activation token for this account.
        activationTokenRepository.deleteAllByUser_Id(user.getId());

        return ActivationResult.success(ACTIVATION_SUCCESS_MESSAGE);
    }

    /** Result of an account activation attempt. */
    public static class ActivationResult {
        private final boolean activated;
        private final boolean enabled;
        private final String message;

        public ActivationResult(boolean activated, boolean enabled, String message) {
            this.activated = activated;
            this.enabled = enabled;
            this.message = message;
        }

        static ActivationResult success(String message) {
            return new ActivationResult(true, true, message);
        }

        static ActivationResult failure(String message) {
            return new ActivationResult(false, false, message);
        }

        static ActivationResult failure(boolean enabled, String message) {
            return new ActivationResult(false, enabled, message);
        }

        public boolean isActivated() {
            return activated;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public String message() {
            return message;
        }
    }

    // ------------------------------------------------------------- dev reset (enhanced)

    @Transactional
    public PasswordOperationResult devResetPassword(String newPassword, String confirmPassword) {
        if (!devResetEnabled || devResetEmail == null || devResetEmail.isBlank()) {
            return PasswordOperationResult.DEV_RESET_NOT_CONFIGURED;
        }
        if (!Objects.equals(newPassword, confirmPassword)) {
            return PasswordOperationResult.CONFIRMATION_MISMATCH;
        }
        if (!PasswordPolicy.isValid(newPassword)) {
            return PasswordOperationResult.PASSWORD_POLICY_VIOLATION;
        }

        String email = EmailNormalizer.normalize(devResetEmail);
        User user = userRepository.findByEmailForUpdate(email).orElse(null);
        if (user == null || !user.isEnabled()) {
            return PasswordOperationResult.DEV_RESET_NOT_CONFIGURED;
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            return PasswordOperationResult.PASSWORD_REUSED;
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));

        // Invalidate all activation tokens and reset tokens for this user
        activationTokenRepository.deleteAllByUser_Id(user.getId());
        tokenRepository.invalidateAllUnusedForUser(user.getId(),
                OffsetDateTime.now(ZoneOffset.UTC));

        // Increment token version to invalidate JWTs
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);

        return PasswordOperationResult.SUCCESS;
    }

    // ------------------------------------------------------------- reset password

    @Transactional
    public PasswordOperationResult resetPassword(String rawTicket,
                                                  String newPassword,
                                                  String confirmPassword) {
        if (!Objects.equals(newPassword, confirmPassword)) {
            return PasswordOperationResult.CONFIRMATION_MISMATCH;
        }
        if (!PasswordPolicy.isValid(newPassword)) {
            return PasswordOperationResult.PASSWORD_POLICY_VIOLATION;
        }
        if (rawTicket == null || rawTicket.isBlank()) {
            return PasswordOperationResult.INVALID_RESET_TOKEN;
        }

        String tokenHash = PasswordResetTokenUtil.hash(rawTicket);
        Long userId = tokenRepository.findUserIdByTokenHash(tokenHash).orElse(null);
        if (userId == null) {
            return PasswordOperationResult.INVALID_RESET_TOKEN;
        }

        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        Optional<PasswordResetToken> tokenOpt = tokenRepository.findByTokenHashForUpdate(tokenHash);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (user == null || !user.isEnabled() || tokenOpt.isEmpty()) {
            return PasswordOperationResult.INVALID_RESET_TOKEN;
        }

        PasswordResetToken token = tokenOpt.get();
        if (token.getUsedAt() != null || !token.getExpiresAt().isAfter(now)) {
            return PasswordOperationResult.INVALID_RESET_TOKEN;
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);

        token.setUsedAt(now);
        tokenRepository.save(token);
        tokenRepository.invalidateOtherUnusedForUser(user.getId(), token.getId(), now);
        activationTokenRepository.deleteAllByUser_Id(user.getId());

        return PasswordOperationResult.SUCCESS;
    }

    // ------------------------------------------------------------- helper methods

    /**
     * Resolve the OTP pepper.
     *
     * <p>A missing pepper is a hard startup failure outside the {@code dev}
     * profile. A shared default would make every deployment hash OTPs with the
     * same publicly known key, so it is deliberately not provided. The
     * {@code dev} profile may omit the value and receives a random per-process
     * pepper instead; OTPs are kept in memory only, so losing them on restart
     * is acceptable for local development.</p>
     */
    private static String resolveOtpPepper(Environment environment) {
        String configured = environment.getProperty("evalora.otp.pepper", "");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (environment.acceptsProfiles(Profiles.of("dev"))) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            log.warn("EVALORA_OTP_PEPPER is not set. Using a random per-process pepper because the dev "
                    + "profile is active; OTPs will not survive a restart. Set EVALORA_OTP_PEPPER locally "
                    + "to keep them stable.");
            return java.util.HexFormat.of().formatHex(random);
        }
        throw new IllegalStateException(
                "EVALORA_OTP_PEPPER (evalora.otp.pepper) must be set to a non-empty secret outside the "
                        + "dev profile. Generate one with: openssl rand -hex 32");
    }

    /** Generate a 6-digit numeric OTP. */
    private String generateOtp() {
        SecureRandom random = new SecureRandom();
        int otp = 100000 + random.nextInt(900000); // 100000 to 999999
        return String.valueOf(otp);
    }

    /** Hash the OTP using HMAC-SHA256 with the pepper. */
    private String hashOtp(String rawOtp) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec keySpec =
                    new javax.crypto.spec.SecretKeySpec(otpPepper.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(rawOtp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash OTP", e);
        }
    }

    /** Invalidate all unused OTP records for a user. */
    private void invalidateAllUnusedOtpsForUser(Long userId) {
        // Remove all cached OTPs for this user that are still unused
        otpCache.entrySet().removeIf(entry -> {
            OtpRecord record = entry.getValue();
            return record.user != null && record.user.getId().equals(userId)
                    && record.usedAt == null;
        });
    }

    /** Send the OTP through the configured email provider. */
private void sendOtp(User user, String rawOtp) {
    if (!emailService.isEmailEnabled()) {
        return;
    }

    String recipientName = employeeRepository.findByUser_Id(user.getId())
            .map(Employee::getFullName)
            .filter(name -> name != null && !name.isBlank())
            .orElse("Evalora User");

    emailService.sendOtpEmail(user.getEmail(), recipientName, rawOtp);
}

    private static int positiveOrDefault(Integer value, int fallback) {
        return value == null || value < 1 ? fallback : value;
    }

    private static int nonNegativeOrDefault(Integer value, int fallback) {
        return value == null || value < 0 ? fallback : value;
    }

    /** Rate limiter for OTP requests per email. */
    private static class OtpRateLimit {
        private static final long WINDOW_MS = TimeUnit.MINUTES.toMillis(15);

        private final int maxRequests;
        private final long cooldownMs;

        private int requestCount = 0;
        private long windowStartMs = System.currentTimeMillis();
        private long lastSendAttemptMs = 0;

        OtpRateLimit(int maxRequests, int cooldownSeconds) {
            this.maxRequests = maxRequests;
            this.cooldownMs = TimeUnit.SECONDS.toMillis(Math.max(0, cooldownSeconds));
        }

        synchronized boolean allowRequest() {
            long now = System.currentTimeMillis();

            // Reset the counter once the 15-minute window has elapsed.
            if (now - windowStartMs >= WINDOW_MS) {
                requestCount = 0;
                windowStartMs = now;
            }

            // Maximum requests per window.
            if (requestCount >= maxRequests) {
                return false;
            }

            // Resend cooldown between accepted requests.
            if (cooldownMs > 0 && lastSendAttemptMs > 0
                    && now - lastSendAttemptMs < cooldownMs) {
                return false;
            }

            requestCount++;
            lastSendAttemptMs = now;
            return true;
        }

        /**
         * A failed delivery did not give the user a code, so the cooldown is
         * released. The request itself still counts towards the window limit.
         */
        synchronized void markDeliveryFailed() {
            lastSendAttemptMs = 0;
        }
    }

    /** Record of an OTP associated with a user. */
    private static class OtpRecord {
        private User user;
        private String otpHash;
        private OffsetDateTime expiresAt;
        private OffsetDateTime usedAt;
        private int failedAttempts;
    }
}