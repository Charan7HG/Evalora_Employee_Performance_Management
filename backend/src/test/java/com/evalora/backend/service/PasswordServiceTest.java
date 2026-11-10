package com.evalora.backend.service;

import com.evalora.backend.entity.PasswordResetToken;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.repository.ActivationTokenRepository;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.PasswordResetTokenRepository;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.util.PasswordResetTokenUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

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
    @Mock
    private EmployeeRepository employeeRepository;
    private PasswordResetTokenRepository tokenRepository;
    private ActivationTokenRepository activationTokenRepository;
    private PasswordEncoder passwordEncoder;
    private EmailService emailService;
    private PasswordService passwordService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        employeeRepository = mock(EmployeeRepository.class);
        tokenRepository = mock(PasswordResetTokenRepository.class);
        activationTokenRepository = mock(ActivationTokenRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        emailService = mock(EmailService.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("evalora.otp.pepper", "test-only-otp-pepper-0123456789abcdef")
                // Existing tests request several OTPs in a row; the dedicated
                // cooldown test below constructs a service with the real default.
                .withProperty("evalora.otp.resend-cooldown", "0");
        passwordService = new PasswordService(userRepository, employeeRepository, tokenRepository,
                activationTokenRepository, passwordEncoder, environment, emailService);
    }

    @Test
    void constructorRejectsMissingOtpPepperOutsideDevProfile() {
        assertThatThrownBy(() -> new PasswordService(userRepository, employeeRepository, tokenRepository,
                activationTokenRepository, passwordEncoder, new MockEnvironment(), emailService))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EVALORA_OTP_PEPPER");
    }

    @Test
    void constructorUsesEphemeralPepperInDevProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        PasswordService service = new PasswordService(userRepository, employeeRepository, tokenRepository,
                activationTokenRepository, passwordEncoder, environment, emailService);

        Object pepper = ReflectionTestUtils.getField(service, "otpPepper");
        assertThat(pepper).isInstanceOf(String.class);
        assertThat((String) pepper).isNotBlank().hasSize(64);
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
        assertThat(user.getTokenVersion()).isEqualTo(2);
        verify(userRepository).save(user);
        verify(tokenRepository).invalidateAllUnusedForUser(eq(1L), any(OffsetDateTime.class));
        verify(activationTokenRepository).deleteAllByUser_Id(1L);
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
    void forgotPasswordThenOtpVerificationCreatesOnlyAHashedTicket() {
        User user = user();
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(emailService.isEmailEnabled()).thenReturn(true);
        when(passwordEncoder.encode(NEW)).thenReturn(NEW_HASH);

        ForgotPasswordResult result = passwordService.forgotPassword("  EMPLOYEE@Evalora.Local  ");

        assertThat(result).isEqualTo(new ForgotPasswordResult(PasswordService.FORGOT_PASSWORD_MESSAGE));
        verifyNoInteractions(tokenRepository);

        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendOtpEmail(eq(EMAIL), eq("Evalora User"), otpCaptor.capture());
        String otp = otpCaptor.getValue();
        assertThat(otp.matches("\\d{6}")).isTrue();

        assertThat(passwordService.verifyOtp(EMAIL, "000000").valid()).isFalse();
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
        VerifyOtpResult verified = passwordService.verifyOtp(EMAIL, otp);
        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);
        assertThat(verified.valid()).isTrue();

        ArgumentCaptor<PasswordResetToken> ticketCaptor =
                ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(ticketCaptor.capture());
        PasswordResetToken ticket = ticketCaptor.getValue();
        ticket.setId(10L);
        assertThat(ticket.getTokenHash()).isEqualTo(PasswordResetTokenUtil.hash(verified.ticket()));
        assertThat(ticket.getUsedAt()).isNull();
        assertThat(ticket.getExpiresAt())
                .isAfterOrEqualTo(before.plusMinutes(15))
                .isBeforeOrEqualTo(after.plusMinutes(15));
        assertThat(passwordService.verifyOtp(EMAIL, otp).valid()).isFalse();
        verify(tokenRepository, times(1)).save(any(PasswordResetToken.class));

        when(tokenRepository.findUserIdByTokenHash(ticket.getTokenHash())).thenReturn(Optional.of(1L));
        when(tokenRepository.findByTokenHashForUpdate(ticket.getTokenHash())).thenReturn(Optional.of(ticket));
        PasswordOperationResult reset = passwordService.resetPassword(verified.ticket(), NEW, NEW);

        assertThat(reset).isEqualTo(PasswordOperationResult.SUCCESS);
        assertThat(user.getPasswordHash()).isEqualTo(NEW_HASH);
        assertThat(user.getTokenVersion()).isEqualTo(2);
        assertThat(ticket.getUsedAt()).isNotNull();
        verify(userRepository).save(user);
        verify(tokenRepository).invalidateOtherUnusedForUser(
                eq(1L), eq(10L), any(OffsetDateTime.class));
        verify(activationTokenRepository).deleteAllByUser_Id(1L);
    }

    @Test
    void expiredOtpCannotIssueAResetTicket() {
        User user = user();
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(emailService.isEmailEnabled()).thenReturn(true);

        passwordService.forgotPassword(EMAIL);

        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendOtpEmail(eq(EMAIL), eq("Evalora User"), otpCaptor.capture());
        Map<?, ?> otpCache = (Map<?, ?>) ReflectionTestUtils.getField(passwordService, "otpCache");
        Object otpRecord = otpCache.values().iterator().next();
        ReflectionTestUtils.setField(otpRecord, "expiresAt", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));

        assertThat(passwordService.verifyOtp(EMAIL, otpCaptor.getValue()).valid()).isFalse();
        verifyNoInteractions(tokenRepository);
    }

    @Test
    void forgotPasswordLimitsOtpRequests() {
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user()));
        when(emailService.isEmailEnabled()).thenReturn(true);

        ForgotPasswordResult result = null;
        for (int request = 0; request < PasswordService.MAX_OTP_REQUESTS + 1; request++) {
            result = passwordService.forgotPassword(EMAIL);
        }

        assertThat(result.message()).isEqualTo(PasswordService.FORGOT_PASSWORD_MESSAGE);
        verify(emailService, times(PasswordService.MAX_OTP_REQUESTS))
                .sendOtpEmail(eq(EMAIL), eq("Evalora User"), any(String.class));
        verifyNoInteractions(tokenRepository);
    }

    @Test
    void forgotPasswordForAnUnknownEmailIsGenericAndStoresNothing() {
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.empty());

        ForgotPasswordResult result = passwordService.forgotPassword(EMAIL);

        assertThat(result.message()).isEqualTo(PasswordService.FORGOT_PASSWORD_MESSAGE);
        verifyNoInteractions(tokenRepository);
    }

    @Test
    void forgotPasswordForADisabledAccountIsGenericAndStoresNothing() {
        User user = user();
        user.setEnabled(false);
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));

        ForgotPasswordResult result = passwordService.forgotPassword(EMAIL);

        assertThat(result.message()).isEqualTo(PasswordService.FORGOT_PASSWORD_MESSAGE);
        verifyNoInteractions(tokenRepository);
    }

    @Test
    void otpDeliveryFailureDiscardsTheOtpAndKeepsTheResponseGeneric() {
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user()));
        when(emailService.isEmailEnabled()).thenReturn(true);
        doThrow(new EmailDeliveryException("simulated provider failure"))
                .when(emailService).sendOtpEmail(eq(EMAIL), eq("Evalora User"), any(String.class));

        ForgotPasswordResult result = passwordService.forgotPassword(EMAIL);

        assertThat(result).isEqualTo(
                new ForgotPasswordResult(PasswordService.FORGOT_PASSWORD_MESSAGE));

        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendOtpEmail(eq(EMAIL), eq("Evalora User"), otpCaptor.capture());
        assertThat(passwordService.verifyOtp(EMAIL, otpCaptor.getValue()).valid()).isFalse();

        Map<?, ?> otpCache = (Map<?, ?>) ReflectionTestUtils.getField(passwordService, "otpCache");
        assertThat(otpCache).isEmpty();
        verifyNoInteractions(tokenRepository);
    }

    @Test
    void aFailedDeliveryDoesNotStartTheResendCooldown() {
        PasswordService service = serviceWithCooldownSeconds(60);
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user()));
        when(emailService.isEmailEnabled()).thenReturn(true);
        doThrow(new EmailDeliveryException("simulated provider failure"))
                .doNothing()
                .when(emailService).sendOtpEmail(eq(EMAIL), eq("Evalora User"), any(String.class));

        ForgotPasswordResult failed = service.forgotPassword(EMAIL);
        ForgotPasswordResult retried = service.forgotPassword(EMAIL);

        assertThat(failed.message()).isEqualTo(PasswordService.FORGOT_PASSWORD_MESSAGE);
        assertThat(retried.message()).isEqualTo(PasswordService.FORGOT_PASSWORD_MESSAGE);
        verify(emailService, times(2))
                .sendOtpEmail(eq(EMAIL), eq("Evalora User"), any(String.class));
    }

    @Test
    void resendCooldownBlocksAnImmediateSecondRequest() {
        PasswordService service = serviceWithCooldownSeconds(60);
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user()));
        when(emailService.isEmailEnabled()).thenReturn(true);

        ForgotPasswordResult first = service.forgotPassword(EMAIL);
        ForgotPasswordResult second = service.forgotPassword(EMAIL);

        assertThat(first.message()).isEqualTo(PasswordService.FORGOT_PASSWORD_MESSAGE);
        assertThat(second.message()).isEqualTo(PasswordService.FORGOT_PASSWORD_MESSAGE);
        verify(emailService, times(1))
                .sendOtpEmail(eq(EMAIL), eq("Evalora User"), any(String.class));
    }

    @Test
    void incorrectOtpAttemptsInvalidateTheCodeAfterTheConfiguredLimit() {
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user()));
        when(emailService.isEmailEnabled()).thenReturn(true);
        passwordService.forgotPassword(EMAIL);

        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendOtpEmail(eq(EMAIL), eq("Evalora User"), otpCaptor.capture());
        String actualOtp = otpCaptor.getValue();
        String incorrectOtp = actualOtp.equals("000000") ? "000001" : "000000";

        for (int attempt = 0; attempt < PasswordService.MAX_OTP_VERIFICATION_ATTEMPTS; attempt++) {
            assertThat(passwordService.verifyOtp(EMAIL, incorrectOtp).valid()).isFalse();
        }

        assertThat(passwordService.verifyOtp(EMAIL, actualOtp).valid()).isFalse();
        verifyNoInteractions(tokenRepository);
    }

    @Test
    void otpUsesTheEmployeeProfileFullNameWhenAvailable() {
        User user = user();
        Employee profile = new Employee();
        profile.setFullName("Asha Rao");
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(employeeRepository.findByUser_Id(1L)).thenReturn(Optional.of(profile));
        when(emailService.isEmailEnabled()).thenReturn(true);

        passwordService.forgotPassword(EMAIL);

        verify(emailService).sendOtpEmail(eq(EMAIL), eq("Asha Rao"), any(String.class));
    }

    @Test
    void blankEmployeeProfileNameFallsBackToTheGenericRecipient() {
        User user = user();
        Employee profile = new Employee();
        profile.setFullName("   ");
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(employeeRepository.findByUser_Id(1L)).thenReturn(Optional.of(profile));
        when(emailService.isEmailEnabled()).thenReturn(true);

        passwordService.forgotPassword(EMAIL);

        verify(emailService).sendOtpEmail(eq(EMAIL), eq("Evalora User"), any(String.class));
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

    private PasswordService serviceWithCooldownSeconds(int cooldownSeconds) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("evalora.otp.pepper", "test-only-otp-pepper-0123456789abcdef")
                .withProperty("evalora.otp.resend-cooldown", String.valueOf(cooldownSeconds));
        return new PasswordService(userRepository, employeeRepository, tokenRepository,
                activationTokenRepository, passwordEncoder, environment, emailService);
    }

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
