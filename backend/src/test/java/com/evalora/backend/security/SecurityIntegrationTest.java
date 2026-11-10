package com.evalora.backend.security;

import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end security tests through MockMvc: the real filter chain, the real
 * JWT filter and the real database.
 *
 * <p>Uses its own in-memory H2 database so it cannot interfere with the
 * persistence tests. No production endpoint exists yet, so two throw-away
 * endpoints are imported from this test class.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityIntegrationTest.TestEndpoints.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "evalora.frontend.base-url=http://localhost:5173",
        "evalora.cors.allowed-origins=http://localhost:5173"
})
class SecurityIntegrationTest {

    private static final String DUMMY_HASH =
            "$2a$12$0123456789012345678901uABCDEFGHIJKLMNOPQRSTUVWXYZ01234";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JwtProperties jwtProperties;

    private User employee;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        employee = userRepository.saveAndFlush(user("employee@evalora.local", Role.EMPLOYEE, true, null));
    }

    // ---------------------------------------------------------------- 401 / 403

    @Test
    void actuatorHealthIsPublicAndReportsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void requestWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/test/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value(RestAuthenticationEntryPoint.MISSING_MESSAGE))
                .andExpect(jsonPath("$.path").value("/api/test/protected"));
    }

    @Test
    void missingTokenDoesNotCreateAuthentication() throws Exception {
        mockMvc.perform(get("/api/test/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/test/protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(RestAuthenticationEntryPoint.INVALID_TOKEN_MESSAGE));
    }

    @Test
    void tokenSignedWithAnotherSecretIsUnauthorized() throws Exception {
        JwtProperties foreignProperties = new JwtProperties();
        foreignProperties.setSecret("another-secret-0123456789abcdef0123456789abcdef");
        foreignProperties.setExpirationMs(28_800_000L);
        String foreignToken = new JwtService(foreignProperties)
                .generateToken("employee@evalora.local", Role.EMPLOYEE);

        mockMvc.perform(get("/api/test/protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + foreignToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(RestAuthenticationEntryPoint.INVALID_TOKEN_MESSAGE));
    }

    @Test
    void expiredTokenIsUnauthorized() throws Exception {
        String expiredToken = expiredTokenFor("employee@evalora.local");

        mockMvc.perform(get("/api/test/protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(RestAuthenticationEntryPoint.INVALID_TOKEN_MESSAGE));
    }

    @Test
    void tokenForAnUnknownAccountIsUnauthorized() throws Exception {
        String token = jwtService.generateToken("ghost@evalora.local", Role.EMPLOYEE);

        mockMvc.perform(get("/api/test/protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(RestAuthenticationEntryPoint.INVALID_TOKEN_MESSAGE));
    }

    // ------------------------------------------------------------ account state

    @Test
    void validTokenAuthenticatesAnEnabledUnlockedUser() throws Exception {
        String token = jwtService.generateToken(employee);

        mockMvc.perform(get("/api/test/protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void disabledUserCannotAuthenticate() throws Exception {
        employee.setEnabled(false);
        userRepository.saveAndFlush(employee);
        String token = jwtService.generateToken(employee);

        mockMvc.perform(get("/api/test/protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value(RestAuthenticationEntryPoint.ACCOUNT_UNAVAILABLE_MESSAGE));
    }

    @Test
    void lockedUserCannotAuthenticate() throws Exception {
        employee.setFailedLoginAttempts(5);
        employee.setLockedUntil(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        userRepository.saveAndFlush(employee);
        String token = jwtService.generateToken(employee);

        mockMvc.perform(get("/api/test/protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value(RestAuthenticationEntryPoint.ACCOUNT_UNAVAILABLE_MESSAGE));
    }

    @Test
    void userWithAnExpiredLockCanAuthenticateAgain() throws Exception {
        employee.setLockedUntil(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        userRepository.saveAndFlush(employee);
        String token = jwtService.generateToken(employee);

        mockMvc.perform(get("/api/test/protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------- database role

    @Test
    void roleIsLoadedFromTheDatabaseAndNotFromTheToken() throws Exception {
        // The token claims MANAGER but the database says EMPLOYEE.
        String token = jwtService.generateToken(employee.getEmail(), Role.MANAGER);

        mockMvc.perform(get("/api/test/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("employee@evalora.local"))
                .andExpect(jsonPath("$.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.authorities.length()").value(1))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_EMPLOYEE"));
    }

    // -------------------------------------------------------------------- CORS

    @Test
    void preflightFromTheFrontendOriginIsAllowed() throws Exception {
        mockMvc.perform(options("/api/test/protected")
                        .header(HttpHeaders.ORIGIN, SecurityConfig.FRONTEND_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        SecurityConfig.FRONTEND_ORIGIN));
    }

    @Test
    void requestFromAnUnapprovedOriginIsRejected() throws Exception {
        mockMvc.perform(options("/api/test/protected")
                        .header(HttpHeaders.ORIGIN, "http://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    // ------------------------------------------------------- public endpoints

    @Test
    void swaggerDocumentationIsNotBlockedBySecurity() throws Exception {
        MvcResult apiDocs = mockMvc.perform(get("/v3/api-docs")).andReturn();
        MvcResult swaggerUi = mockMvc.perform(get("/swagger-ui/index.html")).andReturn();

        assertThat(apiDocs.getResponse().getStatus()).isNotIn(401, 403);
        assertThat(swaggerUi.getResponse().getStatus()).isNotIn(401, 403);
    }

    @Test
    void otpVerificationIsPublicWithoutMakingOtherAuthRoutesPublic() throws Exception {
        mockMvc.perform(post("/api/auth/verify-reset-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"person@example.test\",\"otp\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));

        mockMvc.perform(post("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"x\",\"newPassword\":\"NewPass@2026\","
                                + "\"confirmPassword\":\"NewPass@2026\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private String expiredTokenFor(String email) {
        return Jwts.builder()
                .subject(email)
                .claim(JwtService.ROLE_CLAIM, Role.EMPLOYEE.name())
                .issuedAt(Date.from(Instant.now().minusSeconds(7_200)))
                .expiration(Date.from(Instant.now().minusSeconds(3_600)))
                .signWith(Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8)),
                        Jwts.SIG.HS256)
                .compact();
    }

    private static User user(String email, Role role, boolean enabled, OffsetDateTime lockedUntil) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(DUMMY_HASH);
        user.setRole(role);
        user.setEnabled(enabled);
        user.setLockedUntil(lockedUntil);
        return user;
    }

    /**
     * Test-only endpoints. They are imported explicitly, so they never exist in
     * a running application and must not be confused with real APIs.
     */
    @RestController
    @RequestMapping("/api/test")
    static class TestEndpoints {

        @GetMapping("/protected")
        Map<String, String> protectedEndpoint() {
            return Map.of("status", "ok");
        }

        @GetMapping("/me")
        Map<String, Object> currentUser(Authentication authentication) {
            EvaloraPrincipal principal = (EvaloraPrincipal) authentication.getPrincipal();
            return Map.of(
                    "email", principal.getUsername(),
                    "role", principal.getRole().name(),
                    "authorities", principal.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority)
                            .toList());
        }
    }
}
