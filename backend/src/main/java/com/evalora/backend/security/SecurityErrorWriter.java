package com.evalora.backend.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Writes the single JSON shape used for 401 and 403 responses.
 *
 * <p>{@link JwtAuthenticationFilter} stores one of the {@code *ERROR} values as
 * a request attribute; the entry point turns it into a safe message. The raw
 * exception is never serialised.</p>
 */
public final class SecurityErrorWriter {

    /** Request attribute set by the JWT filter when a token is unusable. */
    public static final String ERROR_ATTRIBUTE = "evalora.security.error";

    /** Token missing from the database, malformed, expired or badly signed. */
    public static final String INVALID_TOKEN = "INVALID_TOKEN";

    /** Token is valid, but the account is disabled or locked. */
    public static final String ACCOUNT_UNAVAILABLE = "ACCOUNT_UNAVAILABLE";

    private SecurityErrorWriter() {
    }

    public static void write(HttpServletResponse response,
                             ObjectMapper objectMapper,
                             int status,
                             String error,
                             String message,
                             String path) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        SecurityErrorResponse body = new SecurityErrorResponse(
                OffsetDateTime.now(ZoneOffset.UTC).toString(), status, error, message, path);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
