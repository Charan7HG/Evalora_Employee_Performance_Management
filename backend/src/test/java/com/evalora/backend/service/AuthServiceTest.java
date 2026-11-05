package com.evalora.backend.service;

import com.evalora.backend.dto.LoginResponse;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.security.JwtProperties;
import com.evalora.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the login and lockout rules. No Spring context and no database:
 * the repository, the password encoder and the token service are mocks.
 */
class AuthServiceTest {

    private static final String EMAIL = "employee@evalora.local";
    private static final String PASSWORD = "Employee@Evalora2026";
    private static final String HASH = "$2a$12$0123456789012345678901uABCDEFGHIJKLMNOPQRSTUVWXYZ01234";
    private static final String TOKEN = "signed.jwt.token";

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private JwtProperties jwtProperties;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);
        jwtProperties = new JwtProperties();
        jwtProperties.setExpirationMs(28_800_000L);
        authService = new AuthService(userRepository, passwordEncoder, jwtService, jwtProperties);
    }

    // ------------------------------------------------------------------ success

    @Test
    void successfulLoginReturnsTokenAndSafeUser() {
        User user = user(2, null);
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn(TOKEN);

        LoginResult result = authService.login(EMAIL, PASSWORD);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.failure()).isNull();
        LoginResponse response = result.response();
        assertThat(response.token()).isEqualTo(TOKEN);
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(28_800L);
        assertThat(response.user().id()).isEqualTo(1L);
        assertThat(response.user().email()).isEqualTo(EMAIL);
        assertThat(response.user().role()).isEqualTo("EMPLOYEE");
    }

    @Test
    void successfulLoginResetsCounterClearsLockAndUpdatesLastLogin() {
        User user = user(4, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn(TOKEN);

        authService.login(EMAIL, PASSWORD);

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getLastLoginAt()).isNotNull();
        verify(userRepository).save(user);
    }

    @Test
    void expiresInComesFromTheConfiguredExpiration() {
        jwtProperties.setExpirationMs(3_600_000L);
        User user = user(0, null);
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn(TOKEN);

        LoginResult result = authService.login(EMAIL, PASSWORD);

        assertThat(result.response().expiresIn()).isEqualTo(3_600L);
    }

    @Test
    void normalisesTheEmailBeforeLookingUpTheUser() {
        User user = user(0, null);
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn(TOKEN);

        LoginResult result = authService.login("  EMPLOYEE@Evalora.Local  ", PASSWORD);

        assertThat(result.isSuccess()).isTrue();
        verify(userRepository).findByEmailForUpdate(EMAIL);
    }

    // ---------------------------------------------------------------- rejection

    @Test
    void unknownEmailReturnsGenericFailure() {
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.empty());

        LoginResult result = authService.login(EMAIL, PASSWORD);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.failure()).isEqualTo(LoginFailure.INVALID_CREDENTIALS);
        assertThat(result.response()).isNull();
        verifyNoInteractions(passwordEncoder, jwtService);
    }

    @Test
    void disabledAccountReturnsGenericFailureAndNeverChecksThePassword() {
        User user = user(0, null);
        user.setEnabled(false);
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));

        LoginResult result = authService.login(EMAIL, PASSWORD);

        assertThat(result.failure()).isEqualTo(LoginFailure.INVALID_CREDENTIALS);
        verifyNoInteractions(passwordEncoder, jwtService);
        verify(userRepository, never()).save(user);
    }

    @Test
    void wrongPasswordIncrementsTheCounter() {
        User user = user(0, null);
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(false);

        LoginResult result = authService.login(EMAIL, PASSWORD);

        assertThat(result.failure()).isEqualTo(LoginFailure.INVALID_CREDENTIALS);
        assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(user.getLockedUntil()).isNull();
        verify(userRepository).save(user);
        verifyNoInteractions(jwtService);
    }

    @Test
    void fourthFailedAttemptDoesNotLockTheAccount() {
        User user = user(3, null);
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(false);

        authService.login(EMAIL, PASSWORD);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(4);
        assertThat(user.getLockedUntil()).isNull();
        verify(userRepository).save(user);
    }

    @Test
    void fifthFailedAttemptLocksTheAccountForFiveMinutes() {
        User user = user(4, null);
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(false);

        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
        authService.login(EMAIL, PASSWORD);
        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(user.getLockedUntil()).isNotNull();
        assertThat(user.getLockedUntil())
                .isAfterOrEqualTo(before.plus(AuthService.LOCK_DURATION))
                .isBeforeOrEqualTo(after.plus(AuthService.LOCK_DURATION));
        verify(userRepository).save(user);
    }

    @Test
    void activeLockRejectsTheAttemptWithoutIncrementingOrCheckingThePassword() {
        User user = user(5, OffsetDateTime.now(ZoneOffset.UTC).plus(Duration.ofMinutes(5)));
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));

        LoginResult result = authService.login(EMAIL, PASSWORD);

        assertThat(result.failure()).isEqualTo(LoginFailure.ACCOUNT_LOCKED);
        assertThat(user.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(user.getLockedUntil()).isNotNull();
        verifyNoInteractions(passwordEncoder, jwtService);
        verify(userRepository, never()).save(user);
    }

    // ------------------------------------------------------------ expired lock

    @Test
    void expiredLockPermitsASuccessfulLoginAndClearsTheOldSequence() {
        User user = user(5, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn(TOKEN);

        LoginResult result = authService.login(EMAIL, PASSWORD);

        assertThat(result.isSuccess()).isTrue();
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void wrongPasswordAfterLockExpiryStartsANewSequence() {
        User user = user(5, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(userRepository.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(false);

        LoginResult result = authService.login(EMAIL, PASSWORD);

        assertThat(result.failure()).isEqualTo(LoginFailure.INVALID_CREDENTIALS);
        assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(user.getLockedUntil()).isNull();
        verify(userRepository).save(user);
    }

    // ----------------------------------------------------------------- helpers

    private static User user(int failedAttempts, OffsetDateTime lockedUntil) {
        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        user.setPasswordHash(HASH);
        user.setRole(Role.EMPLOYEE);
        user.setEnabled(true);
        user.setFailedLoginAttempts(failedAttempts);
        user.setLockedUntil(lockedUntil);
        return user;
    }
}
