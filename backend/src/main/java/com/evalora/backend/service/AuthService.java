package com.evalora.backend.service;

import com.evalora.backend.dto.AuthUserResponse;
import com.evalora.backend.dto.LoginResponse;
import com.evalora.backend.entity.User;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.security.JwtProperties;
import com.evalora.backend.security.JwtService;
import com.evalora.backend.util.EmailNormalizer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Authentication service: credential check, failed-attempt tracking, lockout
 * and token creation.
 *
 * <p>The whole attempt runs in one transaction and locks the user row
 * ({@link UserRepository#findByEmailForUpdate(String)}), so concurrent attempts
 * cannot lose increments.</p>
 */
@Service
public class AuthService {

    /** Five failed attempts lock the account. */
    static final int MAX_FAILED_ATTEMPTS = 5;

    /** The lock lasts five minutes. */
    static final Duration LOCK_DURATION = Duration.ofMinutes(5);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    /**
     * Checks one login attempt.
     *
     * <p>Order matters: unknown email, disabled account and wrong password all
     * return the same failure, while an actively locked account is reported
     * separately. A disabled account is rejected before the password is even
     * verified.</p>
     */
    @Transactional
    public LoginResult login(String rawEmail, String rawPassword) {
        String email = EmailNormalizer.normalize(rawEmail);
        User user = userRepository.findByEmailForUpdate(email).orElse(null);

        if (user == null || !user.isEnabled()) {
            return LoginResult.failure(LoginFailure.INVALID_CREDENTIALS);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        if (isLockActive(user, now)) {
            // Do not touch the counter while the lock is active. The remaining
            // time is computed on the server so the browser never guesses it.
            return LoginResult.locked(remainingLockSeconds(user, now));
        }

        // The lock has expired: forget the old sequence before checking again.
        clearExpiredLock(user);

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            registerFailedAttempt(user, now);
            userRepository.save(user);
            return LoginResult.failure(LoginFailure.INVALID_CREDENTIALS);
        }

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        userRepository.save(user);

        String token = jwtService.generateToken(user);
        long expiresInSeconds = jwtProperties.getExpirationMs() / 1000;

        return LoginResult.success(new LoginResponse(
                token, "Bearer", expiresInSeconds, AuthUserResponse.from(user)));
    }

    private static boolean isLockActive(User user, OffsetDateTime now) {
        return user.getLockedUntil() != null && user.getLockedUntil().isAfter(now);
    }

    /** Whole seconds until the lock expires, rounded up, so it is never reported as 0. */
    private static long remainingLockSeconds(User user, OffsetDateTime now) {
        long millis = Duration.between(now, user.getLockedUntil()).toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }

    private static void clearExpiredLock(User user) {
        if (user.getLockedUntil() != null) {
            user.setLockedUntil(null);
            user.setFailedLoginAttempts(0);
        }
    }

    private static void registerFailedAttempt(User user, OffsetDateTime now) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            user.setLockedUntil(now.plus(LOCK_DURATION));
        }
    }
}
