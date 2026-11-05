package com.evalora.backend.security;

import com.evalora.backend.entity.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for token creation and parsing. No Spring context and no database.
 */
class JwtServiceTest {

    private static final String SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String OTHER_SECRET =
            "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
    private static final String EMAIL = "employee@evalora.local";

    private static JwtService serviceWith(String secret, long expirationMs) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setExpirationMs(expirationMs);
        return new JwtService(properties);
    }

    private final JwtService jwtService = serviceWith(SECRET, 28_800_000L);

    @Test
    void generatesAValidToken() {
        String token = jwtService.generateToken(EMAIL, Role.EMPLOYEE);

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
        assertThat(jwtService.isTokenValid(token)).isTrue();
    }

    @Test
    void extractsTheNormalisedEmailAsSubject() {
        String token = jwtService.generateToken("  " + EMAIL.toUpperCase() + "  ", Role.EMPLOYEE);

        assertThat(jwtService.extractEmail(token)).isEqualTo(EMAIL);
    }

    @Test
    void extractsTheRoleClaim() {
        String token = jwtService.generateToken(EMAIL, Role.MANAGER);

        assertThat(jwtService.extractRole(token)).isEqualTo(Role.MANAGER);
    }

    @Test
    void writesIssuedAtAndExpirationClaims() {
        String token = jwtService.generateToken(EMAIL, Role.HR);

        Claims claims = jwtService.parseClaims(token);

        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
        Instant expiration = jwtService.extractExpiration(token);
        assertThat(Duration.between(Instant.now(), expiration))
                .isBetween(Duration.ofHours(7).plusMinutes(59), Duration.ofHours(8).plusMinutes(1));
    }

    @Test
    void rejectsAnExpiredToken() {
        String expiredToken = Jwts.builder()
                .subject(EMAIL)
                .claim(JwtService.ROLE_CLAIM, Role.EMPLOYEE.name())
                .issuedAt(Date.from(Instant.now().minusSeconds(7_200)))
                .expiration(Date.from(Instant.now().minusSeconds(3_600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.extractEmail(expiredToken))
                .isInstanceOf(ExpiredJwtException.class);
        assertThat(jwtService.isTokenValid(expiredToken)).isFalse();
    }

    @Test
    void rejectsAMalformedToken() {
        assertThatThrownBy(() -> jwtService.extractEmail("this-is-not-a-jwt"))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> jwtService.extractEmail(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jwtService.isTokenValid("a.b.c")).isFalse();
    }

    @Test
    void rejectsATokenSignedWithAnotherSecret() {
        JwtService otherService = serviceWith(OTHER_SECRET, 28_800_000L);
        String foreignToken = otherService.generateToken(EMAIL, Role.EMPLOYEE);

        assertThatThrownBy(() -> jwtService.extractEmail(foreignToken))
                .isInstanceOf(JwtException.class);
        assertThat(jwtService.isTokenValid(foreignToken)).isFalse();
    }

    @Test
    void failsFastWhenTheSecretIsMissing() {
        assertThatThrownBy(() -> serviceWith(null, 28_800_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret is not configured");

        assertThatThrownBy(() -> serviceWith("   ", 28_800_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret is not configured");
    }

    @Test
    void failsFastWhenTheSecretIsTooShort() {
        assertThatThrownBy(() -> serviceWith("too-short", 28_800_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void failsFastWhenTheExpirationIsNotPositive() {
        assertThatThrownBy(() -> serviceWith(SECRET, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expiration-ms");
    }
}
