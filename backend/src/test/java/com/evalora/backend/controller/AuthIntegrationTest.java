package com.evalora.backend.controller;

import com.evalora.backend.dto.LoginResponse;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end authentication tests through the real filter chain, the real
 * controller, the real service and an H2 database in PostgreSQL mode.
 *
 * <p>Uses its own in-memory database so it cannot interfere with the Phase 3A
 * repository tests or the Phase 3B1 security tests. The BCrypt hash is encoded
 * once with strength 12 - the same strength as the production encoder.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_auth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class AuthIntegrationTest {

    private static final String EMAIL = "employee@evalora.local";
    private static final String PASSWORD = "Employee@Evalora2026";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);

    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid email or password.";
    private static final String ACCOUNT_LOCKED_MESSAGE =
            "Your account is temporarily blocked. Try again after 5 minutes.";
    private static final String AUTHENTICATION_REQUIRED_MESSAGE =
            "Authentication is required to access this resource.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private User employee;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        employee = userRepository.saveAndFlush(user(EMAIL, Role.EMPLOYEE, true, null));
    }

    // ------------------------------------------------------------------- login

    @Test
    void successfulLoginReturnsTokenAndSafeUser() throws Exception {
        MvcResult result = mockMvc.perform(loginRequest(EMAIL, PASSWORD))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(28_800))
                .andExpect(jsonPath("$.user.id").value(employee.getId()))
                .andExpect(jsonPath("$.user.email").value(EMAIL))
                .andExpect(jsonPath("$.user.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.failedLoginAttempts").doesNotExist())
                .andExpect(jsonPath("$.user.lockedUntil").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(PASSWORD_HASH).doesNotContain("$2a$");
    }

    @Test
    void successfulLoginReturnsAValidJwt() throws Exception {
        String token = loginSuccessfully(EMAIL, PASSWORD).token();

        assertThat(jwtService.isTokenValid(token)).isTrue();
        assertThat(jwtService.extractEmail(token)).isEqualTo(EMAIL);
        assertThat(jwtService.extractRole(token)).isEqualTo(Role.EMPLOYEE);
        assertThat(Duration.between(Instant.now(), jwtService.extractExpiration(token)))
                .isBetween(Duration.ofMinutes(479), Duration.ofMinutes(481));
    }

    @Test
    void emailIsNormalisedBeforeLookup() throws Exception {
        LoginResponse response = loginSuccessfully("  EMPLOYEE@Evalora.Local  ", PASSWORD);

        assertThat(response.user().email()).isEqualTo(EMAIL);
        assertThat(response.user().role()).isEqualTo("EMPLOYEE");
    }

    @Test
    void unknownEmailReturnsTheGenericFailure() throws Exception {
        assertGenericFailure("nobody@evalora.local", PASSWORD);
    }

    @Test
    void incorrectPasswordReturnsTheSameGenericFailure() throws Exception {
        assertGenericFailure(EMAIL, "WrongPassword2026!");
    }

    @Test
    void disabledAccountCannotLogIn() throws Exception {
        employee.setEnabled(false);
        userRepository.saveAndFlush(employee);

        assertGenericFailure(EMAIL, PASSWORD);

        // The failure must not reveal the account state either.
        mockMvc.perform(loginRequest(EMAIL, PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(INVALID_CREDENTIALS_MESSAGE));
    }

    @Test
    void emptyEmailIsRejectedWithBadRequest() throws Exception {
        mockMvc.perform(loginRequest("", PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Email is required."));
    }

    @Test
    void emptyPasswordIsRejectedWithBadRequest() throws Exception {
        mockMvc.perform(loginRequest(EMAIL, ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Password is required."));
    }

    // ----------------------------------------------------------------- lockout

    @Test
    void firstFailedAttemptIncrementsTheCounterToOne() throws Exception {
        assertGenericFailure(EMAIL, "WrongPassword2026!");

        User reloaded = reloadEmployee();
        assertThat(reloaded.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(reloaded.getLockedUntil()).isNull();
    }

    @Test
    void fourthFailedAttemptDoesNotLockTheAccount() throws Exception {
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertGenericFailure(EMAIL, "WrongPassword2026!");
        }

        User reloaded = reloadEmployee();
        assertThat(reloaded.getFailedLoginAttempts()).isEqualTo(4);
        assertThat(reloaded.getLockedUntil()).isNull();
    }

    @Test
    void fifthFailedAttemptLocksTheAccountForFiveMinutes() throws Exception {
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertGenericFailure(EMAIL, "WrongPassword2026!");
        }
        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);

        User reloaded = reloadEmployee();
        assertThat(reloaded.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(reloaded.getLockedUntil()).isNotNull();
        assertThat(reloaded.getLockedUntil())
                .isAfterOrEqualTo(before.plusMinutes(5))
                .isBeforeOrEqualTo(after.plusMinutes(5));
    }

    @Test
    void sixthAttemptDuringAnActiveLockDoesNotIncrementTheCounter() throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertGenericFailure(EMAIL, "WrongPassword2026!");
        }

        assertLockedFailure(EMAIL, "WrongPassword2026!");

        User reloaded = reloadEmployee();
        assertThat(reloaded.getFailedLoginAttempts()).isEqualTo(5);
    }

    @Test
    void loginIsRejectedWhileTheAccountIsLocked() throws Exception {
        employee.setFailedLoginAttempts(5);
        employee.setLockedUntil(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        userRepository.saveAndFlush(employee);

        assertLockedFailure(EMAIL, PASSWORD);

        User reloaded = reloadEmployee();
        assertThat(reloaded.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(reloaded.getLockedUntil()).isNotNull();
    }

    @Test
    void lockedLoginResponseReportsTheRemainingLockSeconds() throws Exception {
        employee.setFailedLoginAttempts(5);
        employee.setLockedUntil(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        userRepository.saveAndFlush(employee);

        MvcResult result = mockMvc.perform(loginRequest(EMAIL, PASSWORD))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.status").value(423))
                .andExpect(jsonPath("$.error").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
                .andExpect(jsonPath("$.lockedUntilEpochSeconds").isNumber())
                .andReturn();

        // The countdown the frontend shows must come from the server deadline.
        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("retryAfterSeconds").asLong()).isBetween(1L, 300L);
        assertThat(body.get("lockedUntilEpochSeconds").asLong())
                .isGreaterThan(OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond() - 5);
    }

    @Test
    void expiredLockPermitsAnotherLoginAttempt() throws Exception {
        employee.setFailedLoginAttempts(5);
        employee.setLockedUntil(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        userRepository.saveAndFlush(employee);

        LoginResponse response = loginSuccessfully(EMAIL, PASSWORD);

        assertThat(response.token()).isNotBlank();
        User reloaded = reloadEmployee();
        assertThat(reloaded.getFailedLoginAttempts()).isZero();
        assertThat(reloaded.getLockedUntil()).isNull();
    }

    @Test
    void incorrectPasswordAfterLockExpiryStartsANewSequence() throws Exception {
        employee.setFailedLoginAttempts(5);
        employee.setLockedUntil(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        userRepository.saveAndFlush(employee);

        assertGenericFailure(EMAIL, "WrongPassword2026!");

        User reloaded = reloadEmployee();
        assertThat(reloaded.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(reloaded.getLockedUntil()).isNull();
    }

    @Test
    void successfulLoginResetsTheCounterAndClearsTheLock() throws Exception {
        employee.setFailedLoginAttempts(3);
        employee.setLockedUntil(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(30));
        userRepository.saveAndFlush(employee);

        loginSuccessfully(EMAIL, PASSWORD);

        User reloaded = reloadEmployee();
        assertThat(reloaded.getFailedLoginAttempts()).isZero();
        assertThat(reloaded.getLockedUntil()).isNull();
    }

    @Test
    void successfulLoginUpdatesLastLoginAt() throws Exception {
        assertThat(reloadEmployee().getLastLoginAt()).isNull();
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);

        loginSuccessfully(EMAIL, PASSWORD);

        User reloaded = reloadEmployee();
        assertThat(reloaded.getLastLoginAt()).isNotNull();
        assertThat(reloaded.getLastLoginAt()).isAfter(before);
    }

    // ------------------------------------------------------------- current user

    @Test
    void currentUserRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value(AUTHENTICATION_REQUIRED_MESSAGE));
    }

    @Test
    void authenticatedUserCanReadTheirOwnDetails() throws Exception {
        String token = loginSuccessfully(EMAIL, PASSWORD).token();

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(employee.getId()))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void clientCannotReadAnotherUserBySupplyingAnId() throws Exception {
        User manager = userRepository.saveAndFlush(
                user("manager@evalora.local", Role.MANAGER, true, null));
        String token = loginSuccessfully(EMAIL, PASSWORD).token();

        mockMvc.perform(get("/api/auth/me")
                        .param("id", manager.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(employee.getId()))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.role").value("EMPLOYEE"));
    }

    @Test
    void returnedRoleReflectsTheCurrentDatabaseRole() throws Exception {
        String token = loginSuccessfully(EMAIL, PASSWORD).token();

        employee.setRole(Role.HR);
        userRepository.saveAndFlush(employee);

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("HR"));
    }

    // ------------------------------------------------------------------ logout

    @Test
    void logoutRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedLogoutReturnsAConfirmation() throws Exception {
        String token = loginSuccessfully(EMAIL, PASSWORD).token();

        mockMvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully."));
    }

    @Test
    void logoutDoesNotRevokeTheTokenBecauseThereIsNoBlacklist() throws Exception {
        String token = loginSuccessfully(EMAIL, PASSWORD).token();

        mockMvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // Stateless logout is a client-side action: the token stays valid until
        // it expires. If a denylist or revocation table existed, this would be 401.
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL));
    }

    // ----------------------------------------------------------------- helpers

    private MockHttpServletRequestBuilder loginRequest(String email, String password) {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password)));
    }

    private LoginResponse loginSuccessfully(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(loginRequest(email, password))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), LoginResponse.class);
    }

    private void assertGenericFailure(String email, String password) throws Exception {
        mockMvc.perform(loginRequest(email, password))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value(INVALID_CREDENTIALS_MESSAGE));
    }

    private void assertLockedFailure(String email, String password) throws Exception {
        mockMvc.perform(loginRequest(email, password))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.status").value(423))
                .andExpect(jsonPath("$.error").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.message").value(ACCOUNT_LOCKED_MESSAGE));
    }

    private User reloadEmployee() {
        return userRepository.findByEmailIgnoreCase(EMAIL).orElseThrow();
    }

    private static User user(String email, Role role, boolean enabled, OffsetDateTime lockedUntil) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(PASSWORD_HASH);
        user.setRole(role);
        user.setEnabled(enabled);
        user.setLockedUntil(lockedUntil);
        return user;
    }
}
