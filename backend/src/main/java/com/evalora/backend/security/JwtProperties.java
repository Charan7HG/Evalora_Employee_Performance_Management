package com.evalora.backend.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT settings, bound from {@code evalora.jwt.*}.
 *
 * <p>{@code secret} comes from the {@code JWT_SECRET} environment variable and
 * is validated by {@link JwtService}: the application refuses to start when it
 * is missing or weaker than 256 bits. There is deliberately no default secret.</p>
 */
@Component
@ConfigurationProperties(prefix = "evalora.jwt")
@Getter
@Setter
public class JwtProperties {

    /** HMAC signing secret, at least 32 bytes of UTF-8 data. */
    private String secret;

    /** Token lifetime in milliseconds. Default: 8 hours. */
    private long expirationMs = 28_800_000L;
}
