package com.evalora.backend.security;

import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.util.EmailNormalizer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Creates and reads Evalora JWTs.
 *
 * <p>Algorithm: HMAC-SHA256 (HS256). The key is the raw UTF-8 bytes of
 * {@code JWT_SECRET}; the constructor rejects anything shorter than 32 bytes
 * (256 bits), which is the minimum HS256 accepts and the reason a missing
 * secret fails at startup instead of at the first login.</p>
 *
 * <p>The {@code role} claim is informational only. Authorization always reads
 * the current role from the database - see {@link JwtAuthenticationFilter}.</p>
 */
@Service
public class JwtService {

    public static final String ROLE_CLAIM = "role";

    /** Claim carrying the user's token version at issue time. */
    public static final String TOKEN_VERSION_CLAIM = "ver";

    private static final long DEFAULT_TOKEN_VERSION = 1L;

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey signingKey;
    private final long expirationMs;
    private final JwtParser parser;

    public JwtService(JwtProperties properties) {
        this.signingKey = buildSigningKey(properties.getSecret());
        if (properties.getExpirationMs() <= 0) {
            throw new IllegalStateException(
                    "evalora.jwt.expiration-ms must be greater than zero (current value: "
                            + properties.getExpirationMs() + ").");
        }
        this.expirationMs = properties.getExpirationMs();
        this.parser = Jwts.parser().verifyWith(signingKey).build();
    }

    /**
     * @param email the account email; normalised before it becomes the subject
     * @param role  the role at the time of issue (informational only)
     */
    public String generateToken(String email, Role role) {
        return buildToken(email, role, DEFAULT_TOKEN_VERSION);
    }

    public String generateToken(User user) {
        return buildToken(user.getEmail(), user.getRole(), user.getTokenVersion());
    }

    private String buildToken(String email, Role role, long tokenVersion) {
        if (role == null) {
            throw new IllegalArgumentException("A role is required to create a token.");
        }
        Instant issuedAt = Instant.now();
        return Jwts.builder()
                .subject(EmailNormalizer.normalize(email))
                .claim(ROLE_CLAIM, role.name())
                .claim(TOKEN_VERSION_CLAIM, tokenVersion)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plusMillis(expirationMs)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verifies the signature and the expiration, then returns the claims.
     *
     * @throws JwtException             when the token is malformed, expired or signed with another key
     * @throws IllegalArgumentException when the token is null or empty
     */
    public Claims parseClaims(String token) {
        return parser.parseSignedClaims(token).getPayload();
    }

    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    public Role extractRole(String token) {
        String role = parseClaims(token).get(ROLE_CLAIM, String.class);
        return role == null ? null : Role.valueOf(role);
    }

    /**
     * Token version at issue time. Tokens created before the version claim
     * existed are treated as version 1, which keeps them valid only until the
     * first password change bumps the account version.
     */
    public long extractTokenVersion(String token) {
        Object raw = parseClaims(token).get(TOKEN_VERSION_CLAIM);
        return raw instanceof Number number ? number.longValue() : DEFAULT_TOKEN_VERSION;
    }

    public Instant extractExpiration(String token) {
        return parseClaims(token).getExpiration().toInstant();
    }

    /** Convenience check for code that only needs a yes/no answer. */
    public boolean isTokenValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    private static SecretKey buildSigningKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT secret is not configured. Set the JWT_SECRET environment variable to a random value "
                            + "of at least 32 characters (for example: openssl rand -hex 32).");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET is too short (" + keyBytes.length + " bytes). HS256 requires at least "
                            + MIN_SECRET_BYTES + " bytes; generate one with: openssl rand -hex 32.");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
