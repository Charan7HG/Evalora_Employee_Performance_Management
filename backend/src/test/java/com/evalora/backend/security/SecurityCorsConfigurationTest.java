package com.evalora.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CORS allow-list must stay explicit: localhost by default, a configured
 * production origin when supplied, and never a wildcard.
 */
class SecurityCorsConfigurationTest {

    private final SecurityConfig securityConfig = new SecurityConfig();

    @Test
    void defaultConfigurationAllowsTheLocalFrontend() {
        CorsConfiguration cors = corsFor(new CorsProperties());

        assertThat(cors.getAllowedOrigins())
                .containsExactly(CorsProperties.DEFAULT_FRONTEND_ORIGIN);
        assertThat(cors.getAllowCredentials()).isFalse();
    }

    @Test
    void configuredOriginsReplaceTheDefault() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(List.of(
                " https://evalora.example.com ",
                "https://admin.evalora.example.com"));

        CorsConfiguration cors = corsFor(properties);

        assertThat(cors.getAllowedOrigins()).containsExactly(
                "https://evalora.example.com",
                "https://admin.evalora.example.com");
    }

    @Test
    void wildcardOriginIsRejected() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(List.of("*"));

        assertThatThrownBy(() -> corsFor(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard");
    }

    @Test
    void wildcardSubdomainPatternIsRejected() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(List.of("https://*.example.com"));

        assertThatThrownBy(() -> corsFor(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard");
    }

    @Test
    void emptyOriginListIsRejected() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(List.of("  ", ""));

        assertThatThrownBy(() -> corsFor(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one");
    }

    private CorsConfiguration corsFor(CorsProperties properties) {
        UrlBasedCorsConfigurationSource source = (UrlBasedCorsConfigurationSource)
                securityConfig.corsConfigurationSource(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/auth/login");
        return source.getCorsConfiguration(request);
    }
}
