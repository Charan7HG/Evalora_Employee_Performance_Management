package com.evalora.backend.controller;

import com.evalora.backend.entity.PasswordResetToken;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.repository.PasswordResetTokenRepository;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.security.JwtService;
import com.evalora.backend.service.PasswordOperationResult;
import com.evalora.backend.service.PasswordService;
import com.evalora.backend.util.PasswordResetTokenUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end password-management tests through the real filter chain, the real
 * controller, the real service and an H2 database in PostgreSQL mode.
 *
 * <p>This class runs without the {@code dev} profile, so forgot-password must
 * never return the raw token. Reset tests seed known tokens directly, which
 * also lets them control expiry and used state.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_password;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
class PasswordManagementIntegrationTest {

    private static final String EMAIL = "employee@evalora.local";
    private static final String PASSWORD = "Employee@2026";
    private static final String NEW_PASSWORD = "NewEmployee@2026";
    private static final String SECOND_PASSWORD = "SecondEmployee@2026";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);

    private static final String POLICY_MESSAGE =
            "Password must be at least 8 characters long and contain a letter, a number and a special character.";
    private static final String FORGOT_MESSAGE =
            "If an account with that email exists, password reset instructions have been made available.";
    private static final String INVALID_TOKEN_MESSAGE = "Invalid or expired reset token.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordService passwordService;

    @Autowired
    private ObjectMapper objectMapper;

    private User employee;

    @BeforeEach
    void setUp() {
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        employee = userRepository.saveAndFlush(user(EMAIL, Role.EMPLOYEE, true));
    }

    // ------------------------------------------------------------ change password

    @Test
    void successfulChangePasswordReplacesTheHashAndOldPasswordStopsWorking() throws Exception {
        String token = jwtService.generateToken(employee);

        mockMvc.perform(changeRequest(token, PASSWORD, NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password changed successfully."));

        User reloaded = reloadEmployee();
        assertThat(passwordEncoder.matches(NEW_PASSWORD, reloaded.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, reloaded.getPasswordHash())).isFalse();

        mockMvc.perform(loginRequest(EMAIL, NEW_PASSWORD)).andExpect(status().isOk());
        mockMvc.perform(loginRequest(EMAIL, PASSWORD)).andExpect(status().isUnauthorized());
    }

    @Test
    void existingJwtRemainsValidAfterAPasswordChange() throws Exception {
        String token = jwtService.generateToken(employee);

        mockMvc.perform(changeRequest(token, PASSWORD, NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isOk());

        // Stateless JWT limitation, documented in SECURITY_DESIGN.md.
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void incorrectCurrentPasswordIsRejected() throws Exception {
        String token = jwtService.generateToken(employee);

        mockMvc.perform(changeRequest(token, "WrongCurrent@2026", NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Current password is incorrect."));

        assertThat(reloadEmployee().getPasswordHash()).isEqualTo(PASSWORD_HASH);
    }

    @Test
    void changePasswordConfirmationMismatchIsRejected() throws Exception {
        String token = jwtService.generateToken(employee);

        mockMvc.perform(changeRequest(token, PASSWORD, NEW_PASSWORD, SECOND_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("New password and confirmation do not match."));

        assertThat(reloadEmployee().getPasswordHash()).isEqualTo(PASSWORD_HASH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ab@1", "12345678@", "Employee@", "Employee2026"})
    void changePasswordRejectsWeakPasswords(String weakPassword) throws Exception {
        String token = jwtService.generateToken(employee);

        mockMvc.perform(changeRequest(token, PASSWORD, weakPassword, weakPassword))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(POLICY_MESSAGE));

        assertThat(reloadEmployee().getPasswordHash()).isEqualTo(PASSWORD_HASH);
    }

    @Test
    void newPasswordMustDifferFromTheCurrentPassword() throws Exception {
        String token = jwtService.generateToken(employee);

        mockMvc.perform(changeRequest(token, PASSWORD, PASSWORD, PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("New password must be different from the current password."));
    }

    @Test
    void unauthenticatedChangePasswordIsRejected() throws Exception {
        mockMvc.perform(put("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "currentPassword", PASSWORD,
                                "newPassword", NEW_PASSWORD,
                                "confirmPassword", NEW_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------ forgot password

    @Test
    void forgotPasswordForEligibleAccountIsGenericAndStoresOnlyAHash() throws Exception {
        // H2 stores TIMESTAMPTZ at microsecond precision and rounds, so an
        // exact nanosecond bound can sit a fraction of a microsecond on either
        // side of the stored value. A one-second guard band keeps this a real
        // "expires in about 15 minutes" assertion without depending on
        // timestamp precision the database never keeps.
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);

        mockMvc.perform(forgotRequest(EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(FORGOT_MESSAGE))
                .andExpect(jsonPath("$.resetToken").doesNotExist());

        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        List<PasswordResetToken> tokens = tokenRepository.findAll();
        assertThat(tokens).hasSize(1);
        PasswordResetToken stored = tokens.getFirst();
        assertThat(stored.getTokenHash()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(stored.getUsedAt()).isNull();
        assertThat(stored.getExpiresAt())
                .isAfterOrEqualTo(before.plusMinutes(15))
                .isBeforeOrEqualTo(after.plusMinutes(15));
    }

    @Test
    void forgotPasswordForUnknownEmailReturnsTheSameGenericResponse() throws Exception {
        mockMvc.perform(forgotRequest("nobody@evalora.local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(FORGOT_MESSAGE))
                .andExpect(jsonPath("$.resetToken").doesNotExist());

        assertThat(tokenRepository.count()).isZero();
    }

    @Test
    void forgotPasswordForDisabledAccountReturnsTheSameGenericResponse() throws Exception {
        employee.setEnabled(false);
        userRepository.saveAndFlush(employee);

        mockMvc.perform(forgotRequest(EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(FORGOT_MESSAGE))
                .andExpect(jsonPath("$.resetToken").doesNotExist());

        assertThat(tokenRepository.count()).isZero();
    }

    @Test
    void blankEmailOnForgotPasswordIsRejected() throws Exception {
        mockMvc.perform(forgotRequest(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email is required."));
    }

    @Test
    void directDevelopmentResetRouteDoesNotExistOutsideTheDevProfile() throws Exception {
        mockMvc.perform(post("/api/auth/dev-reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "newPassword", NEW_PASSWORD,
                                "confirmPassword", NEW_PASSWORD))))
                .andExpect(status().isNotFound());

        assertThat(passwordEncoder.matches(PASSWORD, reloadEmployee().getPasswordHash())).isTrue();
    }

    // ------------------------------------------------------------- reset password

    @Test
    void successfulResetChangesThePasswordAndConsumesTheToken() throws Exception {
        seedToken("reset-token", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10), null);

        mockMvc.perform(resetRequest("reset-token", NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password changed successfully."));

        User reloaded = reloadEmployee();
        assertThat(passwordEncoder.matches(NEW_PASSWORD, reloaded.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, reloaded.getPasswordHash())).isFalse();
        assertThat(reloadToken("reset-token").getUsedAt()).isNotNull();
    }

    @Test
    void resetInvalidatesOtherOutstandingTokens() throws Exception {
        seedToken("current-token", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10), null);
        seedToken("other-token", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10), null);

        mockMvc.perform(resetRequest("current-token", NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isOk());

        assertThat(reloadToken("current-token").getUsedAt()).isNotNull();
        assertThat(reloadToken("other-token").getUsedAt()).isNotNull();
    }

    @Test
    void invalidResetTokenIsRejected() throws Exception {
        mockMvc.perform(resetRequest("not-a-real-token", NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID_TOKEN_MESSAGE));

        assertThat(reloadEmployee().getPasswordHash()).isEqualTo(PASSWORD_HASH);
    }

    @Test
    void expiredResetTokenIsRejected() throws Exception {
        seedToken("expired-token", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), null);

        mockMvc.perform(resetRequest("expired-token", NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID_TOKEN_MESSAGE));

        assertThat(reloadToken("expired-token").getUsedAt()).isNull();
        assertThat(reloadEmployee().getPasswordHash()).isEqualTo(PASSWORD_HASH);
    }

    @Test
    void alreadyUsedResetTokenIsRejected() throws Exception {
        seedToken("used-token", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10),
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));

        mockMvc.perform(resetRequest("used-token", NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID_TOKEN_MESSAGE));

        assertThat(reloadEmployee().getPasswordHash()).isEqualTo(PASSWORD_HASH);
    }

    @Test
    void resetConfirmationMismatchIsRejectedAndLeavesTheTokenUnused() throws Exception {
        seedToken("mismatch-token", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10), null);

        mockMvc.perform(resetRequest("mismatch-token", NEW_PASSWORD, SECOND_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("New password and confirmation do not match."));

        assertThat(reloadToken("mismatch-token").getUsedAt()).isNull();
        assertThat(reloadEmployee().getPasswordHash()).isEqualTo(PASSWORD_HASH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ab@1", "12345678@", "Employee@", "Employee2026"})
    void resetRejectsWeakPasswords(String weakPassword) throws Exception {
        seedToken("weak-password-token", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10), null);

        mockMvc.perform(resetRequest("weak-password-token", weakPassword, weakPassword))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(POLICY_MESSAGE));

        assertThat(reloadToken("weak-password-token").getUsedAt()).isNull();
        assertThat(reloadEmployee().getPasswordHash()).isEqualTo(PASSWORD_HASH);
    }

    @Test
    void disabledUserCannotResetPassword() throws Exception {
        employee.setEnabled(false);
        userRepository.saveAndFlush(employee);
        seedToken("disabled-user-token", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10), null);

        mockMvc.perform(resetRequest("disabled-user-token", NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID_TOKEN_MESSAGE));

        assertThat(reloadToken("disabled-user-token").getUsedAt()).isNull();
        assertThat(reloadEmployee().getPasswordHash()).isEqualTo(PASSWORD_HASH);
    }

    @Test
    void resetTokenCannotBeReused() throws Exception {
        seedToken("single-use-token", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10), null);

        mockMvc.perform(resetRequest("single-use-token", NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isOk());

        mockMvc.perform(resetRequest("single-use-token", SECOND_PASSWORD, SECOND_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID_TOKEN_MESSAGE));

        assertThat(passwordEncoder.matches(NEW_PASSWORD, reloadEmployee().getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(SECOND_PASSWORD, reloadEmployee().getPasswordHash())).isFalse();
    }

    @Test
    void concurrentResetRequestsCannotConsumeTheSameTokenTwice() throws Exception {
        seedToken("concurrent-token", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10), null);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<PasswordOperationResult> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return passwordService.resetPassword("concurrent-token", NEW_PASSWORD, NEW_PASSWORD);
            });
            Future<PasswordOperationResult> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return passwordService.resetPassword("concurrent-token", SECOND_PASSWORD, SECOND_PASSWORD);
            });

            ready.await();
            start.countDown();

            List<PasswordOperationResult> results = List.of(first.get(), second.get());
            assertThat(results).containsExactlyInAnyOrder(
                    PasswordOperationResult.SUCCESS,
                    PasswordOperationResult.INVALID_RESET_TOKEN);
        } finally {
            executor.shutdownNow();
        }

        assertThat(reloadToken("concurrent-token").getUsedAt()).isNotNull();
        User reloaded = reloadEmployee();
        boolean firstWon = passwordEncoder.matches(NEW_PASSWORD, reloaded.getPasswordHash());
        boolean secondWon = passwordEncoder.matches(SECOND_PASSWORD, reloaded.getPasswordHash());
        assertThat(firstWon ^ secondWon).isTrue();
    }

    // ------------------------------------------------------------------- helpers

    private MockHttpServletRequestBuilder changeRequest(String token, String currentPassword,
                                                        String newPassword, String confirmPassword) {
        return put("/api/auth/change-password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "currentPassword", currentPassword,
                        "newPassword", newPassword,
                        "confirmPassword", confirmPassword)));
    }

    private MockHttpServletRequestBuilder forgotRequest(String email) {
        return post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email)));
    }

    private MockHttpServletRequestBuilder resetRequest(String token, String newPassword,
                                                       String confirmPassword) {
        return post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "token", token,
                        "newPassword", newPassword,
                        "confirmPassword", confirmPassword)));
    }

    private MockHttpServletRequestBuilder loginRequest(String email, String password) {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", password)));
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private PasswordResetToken seedToken(String rawToken, OffsetDateTime expiresAt,
                                         OffsetDateTime usedAt) {
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(employee);
        token.setTokenHash(PasswordResetTokenUtil.hash(rawToken));
        token.setExpiresAt(expiresAt);
        token.setUsedAt(usedAt);
        return tokenRepository.saveAndFlush(token);
    }

    private User reloadEmployee() {
        return userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow();
    }

    private PasswordResetToken reloadToken(String rawToken) {
        return tokenRepository.findByTokenHash(PasswordResetTokenUtil.hash(rawToken)).orElseThrow();
    }

    private static User user(String email, Role role, boolean enabled) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(PASSWORD_HASH);
        user.setRole(role);
        user.setEnabled(enabled);
        return user;
    }
}
