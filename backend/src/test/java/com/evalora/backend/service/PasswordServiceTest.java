package com.evalora.backend.service;

import com.evalora.backend.entity.PasswordResetToken;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.repository.PasswordResetTokenRepository;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.util.PasswordResetTokenUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for password change, forgot-password and reset-password. No Spring
 * context and no database: repositories and the encoder are mocks.
 */
class PasswordServiceTest {

    private static final String EMAIL = "employee@evalora.local";
    private static final String CURRENT = "Current@2026";
    private static final String NEW = "NewPass@2026";
    private static final String OTHER = "OtherPass@2026";
    private static final String HASH = "$2a$12$0123456789012345678901uABCDEFGHIJKLMNOPQRSTUVWXYZ01234";
    private static final String NEW_HASH = "$2a$12$9876543210987654321098uABCDEFGHIJKLMNOPQRSTUVWXYZ01234";

    private UserRepository userRepository;
    private PasswordResetTokenRepository tokenRepository;
    private PasswordEncoder passwordEncoder;
    private PasswordService passwordService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        tokenRepository = mock(PasswordResetTokenRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        passwordService = new PasswordService(userRepository, tokenRepository, passwordEncoder,
                new MockEnvironment());
    }

    // ------------------------------------------------------------ change password

    @Test
    void changePasswordHashesAndSavesTheNewPassword() {
        User user = user();
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(CURRENT, HASH)).thenReturn(true);
        when(passwordEncoder.matches(NEW, HASH)).thenReturn(false);
        when(passwordEncoder.encode(NEW)).thenReturn(NEW_HASH);

        PasswordOperationResult result =
                passwordService.changePassword(1L, CURRENT, NEW, NEW);

        assertThat(result).isEqualTo(PasswordOperationResult.SUCCESS);
        assertThat(user.getPasswordHash()).isEqualTo(NEW_HASH);
        verify(userRepository).save(user);
    }

    @Test
    void changePasswordRejectsAnIncorrectCurrentPassword() {
        User user = user();
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(CURRENT, HASH)).thenReturn(false);

        PasswordOperationResult result =
                passwordService.changePassword(1L, CURRENT, NEW, NEW);

        assertThat(result).isEqualTo(PasswordOperationResult.CURRENT_PASSWORD_INCORRECT);
        verify(userRepository, never()).save(user);
    }

    @Test
    void changePasswordRejectsAConfirmationMismatchBeforeUsingTheDatabase() {
        PasswordOperationResult result =
                passwordService.changePassword(1L, CURRENT, NEW, OTHER);

        assertThat(result).isEqualTo(PasswordOperationResult.CONFIRMATION_MISMATCH);
        verifyNoInteractions(userRepository, passwordEncoder);
    }

    @Test
    void changePasswordRejectsAPasswordThatBreaksThePolicy() {
        PasswordOperationResult result =
                passwordService.changePassword(1L, CURRENT, "short", "short");

        assertThat(result).isEqualTo(PasswordOperationResult.PASSWORD_POLICY_VIOLATION);
        verifyNoInteractions(userRepository, passwordEncoder);
    }

    @Test
    void changePasswordRejectsReusingTheCurrentPassword() {
        User user = user();
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(CURRENT, HASH)).thenReturn(true);
        when(passwordEncoder.matches(NEW, HASH)).thenReturn(true);

        PasswordOperationResult result =
                passwordService.changePassword(1L, CURRENT, NEW, NEW);

        assertThat(result).isEqualTo(PasswordOperationResult.PASSWORD_REUSED);
        verify(userRepository, never()).save(user);
    }

    // ------------------------------------------------------------ forgot password

    @Test
    void forgotPasswordStoresOnlyTheHashAndAQualifiedExpiry() {
        User user = user();
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));

        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
        ForgotPasswordResult result = passwordService.forgotPassword("  EMPLOYEE@Evalora.Local  ");
        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);

        ArgumentCaptor<PasswordResetToken> captor =
                ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(captor.capture());
        PasswordResetToken stored = captor.getValue();

        assertThat(result.message()).isEqualTo(PasswordService.FORGOT_PASSWORD_MESSAGE);
        assertThat(result.resetToken()).isNull();
        assertThat(stored.getTokenHash()).hasSize(64);
        assertThat(stored.getUsedAt()).isNull();
        assertThat(stored.getExpiresAt())
                .isAfterOrEqualTo(before.plusMinutes(15))
                .isBeforeOrEqualTo(after.plusMinutes(15));
        verify(tokenRepository).invalidateAllUnusedForUser(eq(1L), any(OffsetDateTime.class));
    }

    @Test
    void devProfileReturnsTheRawTokenAndStoresItsHashOnly() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        PasswordService devService = new PasswordService(
                userRepository, tokenRepository, passwordEncoder, environment);

        User user = user();
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));

        ForgotPasswordResult result = devService.forgotPassword(EMAIL);

        ArgumentCaptor<PasswordResetToken> captor =
                ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(captor.capture());

        assertThat(result.resetToken()).isNotBlank().hasSize(64);
        assertThat(captor.getValue().getTokenHash())
                .isEqualTo(PasswordResetTokenUtil.hash(result.resetToken()))
                .isNotEqualTo(result.resetToken());
    }

    @Test
    void forgotPasswordForAnUnknownEmailIsGenericAndStoresNothing() {
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.empty());

        ForgotPasswordResult result = passwordService.forgotPassword(EMAIL);

        assertThat(result.message()).isEqualTo(PasswordService.FORGOT_PASSWORD_MESSAGE);
        assertThat(result.resetToken()).isNull();
        verifyNoInteractions(tokenRepository);
    }

    @Test
    void forgotPasswordForADisabledAccountIsGenericAndStoresNothing() {
        User user = user();
        user.setEnabled(false);
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));

        ForgotPasswordResult result = passwordService.forgotPassword(EMAIL);

        assertThat(result.message()).isEqualTo(PasswordService.FORGOT_PASSWORD_MESSAGE);
        assertThat(result.resetToken()).isNull();
        verifyNoInteractions(tokenRepository);
    }

    // ------------------------------------------------------------- reset password

    @Test
    void resetPasswordChangesThePasswordAndConsumesTheToken() {
        User user = user();
        PasswordResetToken token = token(user, "raw-token", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10), null);
        String hash = token.getTokenHash();

        when(tokenRepository.findUserIdByTokenHash(hash)).thenReturn(Optional.of(1L));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(tokenRepository.findByTokenHashForUpdate(hash)).thenReturn(Optional.of(token));
        when(passwordEncoder.encode(NEW)).thenReturn(NEW_HASH);

        PasswordOperationResult result = passwordService.resetPassword("raw-token", NEW, NEW);

        assertThat(result).isEqualTo(PasswordOperationResult.SUCCESS);
        assertThat(user.getPasswordHash()).isEqualTo(NEW_HASH);
        assertThat(token.getUsedAt()).isNotNull();
        verify(userRepository).save(user);
        verify(tokenRepository).save(token);
        verify(tokenRepository).invalidateOtherUnusedForUser(
                eq(1L), eq(10L), any(OffsetDateTime.class));
    }

    @Test
    void resetPasswordRejectsAnUnknownToken() {
        String hash = PasswordResetTokenUtil.hash("missing");
        when(tokenRepository.findUserIdByTokenHash(hash)).thenReturn(Optional.empty());

        PasswordOperationResult result =
                passwordService.resetPassword("missing", NEW, NEW);

        assertThat(result).isEqualTo(PasswordOperationResult.INVALID_RESET_TOKEN);
        verifyNoInteractions(userRepository, passwordEncoder);
    }

    @Test
    void resetPasswordRejectsAnExpiredToken() {
        User user = user();
        PasswordResetToken token = token(user, "expired-token",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), null);
        stubTokenLookup(user, token);

        PasswordOperationResult result =
                passwordService.resetPassword("expired-token", NEW, NEW);

        assertThat(result).isEqualTo(PasswordOperationResult.INVALID_RESET_TOKEN);
        assertThat(user.getPasswordHash()).isEqualTo(HASH);
        assertThat(token.getUsedAt()).isNull();
        verify(userRepository, never()).save(user);
        verify(tokenRepository, never()).save(token);
    }

    @Test
    void resetPasswordRejectsAnAlreadyUsedToken() {
        User user = user();
        PasswordResetToken token = token(user, "used-token",
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10),
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        stubTokenLookup(user, token);

        PasswordOperationResult result =
                passwordService.resetPassword("used-token", NEW, NEW);

        assertThat(result).isEqualTo(PasswordOperationResult.INVALID_RESET_TOKEN);
        assertThat(user.getPasswordHash()).isEqualTo(HASH);
        verify(userRepository, never()).save(user);
    }

    @Test
    void resetPasswordRejectsADisabledUser() {
        User user = user();
        user.setEnabled(false);
        PasswordResetToken token = token(user, "disabled-user-token",
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10), null);
        stubTokenLookup(user, token);

        PasswordOperationResult result =
                passwordService.resetPassword("disabled-user-token", NEW, NEW);

        assertThat(result).isEqualTo(PasswordOperationResult.INVALID_RESET_TOKEN);
        assertThat(user.getPasswordHash()).isEqualTo(HASH);
        assertThat(token.getUsedAt()).isNull();
        verify(userRepository, never()).save(user);
    }

    @Test
    void resetPasswordRejectsConfirmationMismatchBeforeUsingTheDatabase() {
        PasswordOperationResult result =
                passwordService.resetPassword("some-token", NEW, OTHER);

        assertThat(result).isEqualTo(PasswordOperationResult.CONFIRMATION_MISMATCH);
        verifyNoInteractions(userRepository, passwordEncoder, tokenRepository);
    }

    @Test
    void resetPasswordRejectsAPasswordThatBreaksThePolicy() {
        PasswordOperationResult result =
                passwordService.resetPassword("some-token", "short", "short");

        assertThat(result).isEqualTo(PasswordOperationResult.PASSWORD_POLICY_VIOLATION);
        verifyNoInteractions(userRepository, passwordEncoder, tokenRepository);
    }

    @Test
    void sameTokenCannotBeConsumedTwice() {
        User user = user();
        PasswordResetToken token = token(user, "single-use-token",
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10), null);
        stubTokenLookup(user, token);
        when(passwordEncoder.encode(NEW)).thenReturn(NEW_HASH);

        PasswordOperationResult first =
                passwordService.resetPassword("single-use-token", NEW, NEW);
        PasswordOperationResult second =
                passwordService.resetPassword("single-use-token", NEW, NEW);

        assertThat(first).isEqualTo(PasswordOperationResult.SUCCESS);
        assertThat(second).isEqualTo(PasswordOperationResult.INVALID_RESET_TOKEN);
    }

    // ------------------------------------------------------------------- helpers

    private void stubTokenLookup(User user, PasswordResetToken token) {
        when(tokenRepository.findUserIdByTokenHash(token.getTokenHash()))
                .thenReturn(Optional.of(user.getId()));
        when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(tokenRepository.findByTokenHashForUpdate(token.getTokenHash()))
                .thenReturn(Optional.of(token));
    }

    private static User user() {
        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        user.setPasswordHash(HASH);
        user.setRole(Role.EMPLOYEE);
        user.setEnabled(true);
        return user;
    }

    private static PasswordResetToken token(User user, String rawToken,
                                            OffsetDateTime expiresAt, OffsetDateTime usedAt) {
        PasswordResetToken token = new PasswordResetToken();
        token.setId(10L);
        token.setUser(user);
        token.setTokenHash(PasswordResetTokenUtil.hash(rawToken));
        token.setExpiresAt(expiresAt);
        token.setUsedAt(usedAt);
        return token;
    }
}
