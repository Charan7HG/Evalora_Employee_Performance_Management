package com.evalora.backend.security;

import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.util.ActivationLinkBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Stateless JWT security for the whole application.
 *
 * <p>Authentication is stateless JWT. There are three path-level role rules:
 * every {@code /api/hr/**} endpoint requires {@code ROLE_HR}, every
 * {@code /api/employee/**} endpoint requires {@code ROLE_EMPLOYEE} and every
 * {@code /api/manager/**} endpoint requires {@code ROLE_MANAGER}. Roles come
 * from the database-backed principal, never from the token or the request
 * body. Everything else remains authenticated-only.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Default browser origin, used when no explicit CORS configuration is
     * supplied. Production overrides it through {@code EVALORA_CORS_ALLOWED_ORIGINS}.
     */
    public static final String FRONTEND_ORIGIN = CorsProperties.DEFAULT_FRONTEND_ORIGIN;

    /**
     * Public infrastructure endpoints. The actuator health endpoint is
     * deliberately included so Docker and Jenkins can probe the container
     * without a token; it exposes only {@code {"status":"UP"}}. No other
     * actuator endpoint is exposed or permitted.
     */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error",
            "/actuator/health"
    };

    /**
     * The public authentication endpoints. Login and the password-recovery
     * calls must work without a token; change-password, /me and /logout do not.
     * The development reset route only has a controller under the dev profile.
     */
    private static final String[] PUBLIC_AUTH_ENDPOINTS = {
            "/api/auth/login",
            "/api/auth/forgot-password",
            "/api/auth/verify-reset-otp",
            "/api/auth/reset-password",
            // Activation links are opened from an email, before the user has a
            // token, so this endpoint must be public.
            "/api/auth/activate-account",
            "/api/auth/dev-reset-password"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtService jwtService,
                                                   UserRepository userRepository,
                                                   RestAuthenticationEntryPoint authenticationEntryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        JwtAuthenticationFilter jwtAuthenticationFilter =
                new JwtAuthenticationFilter(jwtService, userRepository);

        http
                // A token in a header is not sent automatically by the browser,
                // so CSRF protection does not apply to this API.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                // No server-side session, no request cache: every request must
                // prove who it is with its own token.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_ENDPOINTS).permitAll()
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers("/api/hr/**").hasRole("HR")
                        // Activation resend/status for staff are HR operations,
                        // even though they live under the auth controller path.
                        .requestMatchers("/api/auth/hr/**").hasRole("HR")
                        .requestMatchers("/api/employee/**").hasRole("EMPLOYEE")
                        .requestMatchers("/api/manager/**").hasRole("MANAGER")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** BCrypt with strength 12. The only place a password is ever hashed. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public RestAuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new RestAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    public RestAccessDeniedHandler restAccessDeniedHandler(ObjectMapper objectMapper) {
        return new RestAccessDeniedHandler(objectMapper);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            CorsProperties corsProperties,
            @Value("${evalora.frontend.base-url}") String frontendBaseUrl) {
        List<String> allowedOrigins = corsProperties.getAllowedOrigins().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();

        if (allowedOrigins.isEmpty()) {
            throw new IllegalStateException(
                    "evalora.cors.allowed-origins must contain at least one explicit origin.");
        }
        if (allowedOrigins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalStateException(
                    "evalora.cors.allowed-origins must not contain a wildcard origin.");
        }

        URI frontend = ActivationLinkBuilder.validateBaseUrl(frontendBaseUrl);
        String scheme = frontend.getScheme().toLowerCase(Locale.ROOT);
        String host = frontend.getHost().toLowerCase(Locale.ROOT);
        int port = frontend.getPort();
        String frontendOrigin = scheme + "://" + host
                + (port < 0 || (scheme.equals("http") && port == 80)
                || (scheme.equals("https") && port == 443) ? "" : ":" + port);
        if (allowedOrigins.stream().noneMatch(origin -> origin.equalsIgnoreCase(frontendOrigin))) {
            throw new IllegalStateException(
                    "evalora.cors.allowed-origins must include the origin from evalora.frontend.base-url: "
                            + frontendOrigin);
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization"));
        // The token travels in the Authorization header, never in a cookie.
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
