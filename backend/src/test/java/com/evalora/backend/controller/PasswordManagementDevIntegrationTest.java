package com.evalora.backend.controller;

import com.evalora.backend.dto.ForgotPasswordDevResponse;
import com.evalora.backend.entity.PasswordResetToken;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.repository.PasswordResetTokenRepository;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.util.PasswordResetTokenUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Development-profile behaviour: forgot-password returns the raw token so the
 * flow can be completed locally without email. The database still stores only
 * the SHA-256 hash.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_password_dev;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "evalora.dev-reset.email=employee@evalora.local"
})
class PasswordManagementDevIntegrationTest {

    private static final String EMAIL = "employee@evalora.local";
    private static final String PASSWORD = "Employee@2026";
    private static final String NEW_PASSWORD = "NewEmployee@2026";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);
    private static final String FORGOT_MESSAGE =
            "If an account with that email exists, password reset instructions have been made available.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private User employee;

    @BeforeEach
    void setUp() {
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        employee = userRepository.saveAndFlush(user(EMAIL, Role.EMPLOYEE, true));
    }

    @Test
    void devProfileReturnsTheRawTokenAndStoresOnlyItsHash() throws Exception {
        // H2 keeps TIMESTAMPTZ at microsecond precision and rounds, so the
        // ~15-minute expiry is compared with a one-second guard band instead
        // of at nanosecond precision the database never stores.
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);

        ForgotPasswordDevResponse response = forgot(EMAIL);

        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1);

        assertThat(response.message()).isEqualTo(FORGOT_MESSAGE);
        assertThat(response.resetToken()).hasSize(64).matches("[0-9a-f]{64}");

        List<PasswordResetToken> tokens = tokenRepository.findAll();
        assertThat(tokens).hasSize(1);
        PasswordResetToken stored = tokens.getFirst();
        assertThat(stored.getTokenHash())
                .isEqualTo(PasswordResetTokenUtil.hash(response.resetToken()))
                .isNotEqualTo(response.resetToken());
        assertThat(stored.getExpiresAt())
                .isAfterOrEqualTo(before.plusMinutes(15))
                .isBeforeOrEqualTo(after.plusMinutes(15));
    }

    @Test
    void previousUnusedTokenIsInvalidatedWhenANewOneIsIssued() throws Exception {
        String firstToken = forgot(EMAIL).resetToken();
        String secondToken = forgot(EMAIL).resetToken();

        assertThat(firstToken).isNotEqualTo(secondToken);

        mockMvc.perform(resetRequest(firstToken, NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid or expired reset token."));

        mockMvc.perform(resetRequest(secondToken, NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isOk());

        assertThat(passwordEncoder.matches(NEW_PASSWORD, reloadEmployee().getPasswordHash())).isTrue();
        assertThat(reloadToken(firstToken).getUsedAt()).isNotNull();
        assertThat(reloadToken(secondToken).getUsedAt()).isNotNull();
    }

    @Test
    void unknownEmailStillReturnsNoTokenInDev() throws Exception {
        mockMvc.perform(forgotRequest("nobody@evalora.local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(FORGOT_MESSAGE))
                .andExpect(jsonPath("$.resetToken").doesNotExist());

        assertThat(tokenRepository.count()).isZero();
    }

    @Test
    void disabledAccountStillReturnsNoTokenInDev() throws Exception {
        employee.setEnabled(false);
        userRepository.saveAndFlush(employee);

        mockMvc.perform(forgotRequest(EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(FORGOT_MESSAGE))
                .andExpect(jsonPath("$.resetToken").doesNotExist());

        assertThat(tokenRepository.count()).isZero();
    }

    // ---------------------------------------------------- direct dev reset

    @Test
    void devResetChangesTheConfiguredAccountWithoutEmailOrToken() throws Exception {
        User other = userRepository.saveAndFlush(user("other@evalora.local", Role.EMPLOYEE, true));
        String otherHash = other.getPasswordHash();

        mockMvc.perform(devResetRequest(NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password changed successfully."));

        User reloaded = reloadEmployee();
        assertThat(passwordEncoder.matches(NEW_PASSWORD, reloaded.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, reloaded.getPasswordHash())).isFalse();

        User untouched = userRepository.findByEmailIgnoreCase("other@evalora.local").orElseThrow();
        assertThat(untouched.getPasswordHash()).isEqualTo(otherHash);
    }

    @Test
    void devResetInvalidatesOutstandingTokens() throws Exception {
        String token = forgot(EMAIL).resetToken();

        mockMvc.perform(devResetRequest(NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isOk());

        assertThat(reloadToken(token).getUsedAt()).isNotNull();
        mockMvc.perform(resetRequest(token, "Another@2026", "Another@2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid or expired reset token."));
    }

    @Test
    void devResetEnforcesConfirmationPolicyAndReuse() throws Exception {
        mockMvc.perform(devResetRequest(NEW_PASSWORD, "Different@2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("New password and confirmation do not match."));

        mockMvc.perform(devResetRequest("short", "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Password must be at least 8 characters long and contain a letter, a number and a special character."));

        mockMvc.perform(devResetRequest(PASSWORD, PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("New password must be different from the current password."));

        assertThat(passwordEncoder.matches(PASSWORD, reloadEmployee().getPasswordHash())).isTrue();
    }

    // ------------------------------------------------------------------- helpers

    private MockHttpServletRequestBuilder devResetRequest(String newPassword, String confirmPassword) {
        return post("/api/auth/dev-reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "newPassword", newPassword,
                        "confirmPassword", confirmPassword)));
    }

    private ForgotPasswordDevResponse forgot(String email) throws Exception {
        MvcResult result = mockMvc.perform(forgotRequest(email))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                ForgotPasswordDevResponse.class);
    }

    private MockHttpServletRequestBuilder forgotRequest(String email) {
        return post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email)));
    }

    private MockHttpServletRequestBuilder resetRequest(String token, String newPassword,
                                                       String confirmPassword) {
        return post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "token", token,
                        "newPassword", newPassword,
                        "confirmPassword", confirmPassword)));
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
