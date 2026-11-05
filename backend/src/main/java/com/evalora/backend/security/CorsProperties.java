package com.evalora.backend.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Browser origins allowed to call the API, bound from
 * {@code evalora.cors.allowed-origins}.
 *
 * <p>The value is a comma-separated list. It defaults to the local Vite
 * development server so an unconfigured checkout keeps working, and production
 * sets {@code EVALORA_CORS_ALLOWED_ORIGINS} to the deployed frontend origin.
 * The wildcard {@code *} is rejected deliberately: the API is not public and
 * every browser origin must be named explicitly.</p>
 */
@Component
@ConfigurationProperties(prefix = "evalora.cors")
@Getter
@Setter
public class CorsProperties {

    /** Default local development origin. */
    public static final String DEFAULT_FRONTEND_ORIGIN = "http://localhost:5173";

    /** Allowed browser origins. Never empty, never {@code *}. */
    private List<String> allowedOrigins = List.of(DEFAULT_FRONTEND_ORIGIN);
}
