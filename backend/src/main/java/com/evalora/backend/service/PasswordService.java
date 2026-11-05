package com.evalora.backend.service;

import com.evalora.backend.entity.PasswordResetToken;
import com.evalora.backend.entity.User;
import com.evalora.backend.repository.PasswordResetTokenRepository;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.util.EmailNormalizer;
import com.evalora.backend.util.PasswordPolicy;
import com.evalora.backend.util.PasswordResetTokenUtil;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Password management: change, forgot and reset.
 *
 * <p>All token and password state changes happen in a transaction. Password
 * reset locks the user row first and the token row second; forgot-password also
 * locks the user row first. That consistent order avoids deadlocks between the
 * two flows, and the token row lock makes a reset token strictly one-time even
 * under concurrent requests.</p>
 */
@Service
public class PasswordService {

    /** Reset tokens are valid for fifteen minutes. */
    static final Duration RESET_TOKEN_LIFETIME = Duration.ofMinutes(15);

    static final String FORGOT_PASSWORD_MESSAGE =
            "If an account with that email exists, password reset instructions have been made available.";

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean exposeResetToken;
    private final String devResetEmail;

    public PasswordService(UserRepository userRepository,
                           PasswordResetTokenRepository tokenRepository,
                           PasswordEncoder passwordEncoder,
                           Environment environment) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.exposeResetToken = environment.acceptsProfiles(Profiles.of("dev"));
        this.devResetEmail = environment.getProperty("evalora.dev-reset.email", "");
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
        userRepository.save(user);
        return PasswordOperationResult.SUCCESS;
    }

    // ------------------------------------------------------------ forgot password

    @Transactional
    public ForgotPasswordResult forgotPassword(String rawEmail) {
        if (rawEmail == null) {
            return new ForgotPasswordResult(FORGOT_PASSWORD_MESSAGE, null);
        }

        String email = EmailNormalizer.normalize(rawEmail);
        User user = userRepository.findByEmailForUpdate(email).orElse(null);

        if (user == null || !user.isEnabled()) {
            return new ForgotPasswordResult(FORGOT_PASSWORD_MESSAGE, null);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        tokenRepository.invalidateAllUnusedForUser(user.getId(), now);

        String rawToken = PasswordResetTokenUtil.generate();
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenHash(PasswordResetTokenUtil.hash(rawToken));
        token.setExpiresAt(now.plus(RESET_TOKEN_LIFETIME));
        tokenRepository.save(token);

        return new ForgotPasswordResult(FORGOT_PASSWORD_MESSAGE,
                exposeResetToken ? rawToken : null);
    }

    // ------------------------------------------------------------- reset password

    /**
     * Development-only direct reset: no email is sent and no token is needed.
     * The account is fixed by {@code evalora.dev-reset.email}, so the caller
     * cannot choose whose password changes. The same confirmation, policy,
     * BCrypt and reuse checks as the normal reset apply, and outstanding
     * reset tokens are invalidated so an old link cannot be replayed.
     */
    @Transactional
    public PasswordOperationResult devResetPassword(String newPassword, String confirmPassword) {
        if (!exposeResetToken || devResetEmail == null || devResetEmail.isBlank()) {
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
        userRepository.save(user);
        tokenRepository.invalidateAllUnusedForUser(user.getId(),
                OffsetDateTime.now(ZoneOffset.UTC));
        return PasswordOperationResult.SUCCESS;
    }

    @Transactional
    public PasswordOperationResult resetPassword(String rawToken,
                                                 String newPassword,
                                                 String confirmPassword) {
        if (!Objects.equals(newPassword, confirmPassword)) {
            return PasswordOperationResult.CONFIRMATION_MISMATCH;
        }
        if (!PasswordPolicy.isValid(newPassword)) {
            return PasswordOperationResult.PASSWORD_POLICY_VIOLATION;
        }
        if (rawToken == null || rawToken.isBlank()) {
            return PasswordOperationResult.INVALID_RESET_TOKEN;
        }

        String tokenHash = PasswordResetTokenUtil.hash(rawToken);

        // Lock order for every password-reset path: user row, then token row.
        Long userId = tokenRepository.findUserIdByTokenHash(tokenHash).orElse(null);
        if (userId == null) {
            return PasswordOperationResult.INVALID_RESET_TOKEN;
        }

        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null) {
            return PasswordOperationResult.INVALID_RESET_TOKEN;
        }

        PasswordResetToken token = tokenRepository.findByTokenHashForUpdate(tokenHash).orElse(null);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        if (token == null
                || token.getUsedAt() != null
                || !token.getExpiresAt().isAfter(now)
                || !user.isEnabled()) {
            return PasswordOperationResult.INVALID_RESET_TOKEN;
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.setUsedAt(now);
        tokenRepository.save(token);
        tokenRepository.invalidateOtherUnusedForUser(user.getId(), token.getId(), now);

        return PasswordOperationResult.SUCCESS;
    }
}
