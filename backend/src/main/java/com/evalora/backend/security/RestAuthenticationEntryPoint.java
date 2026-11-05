package com.evalora.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Returns 401 with a JSON body whenever a request reaches a protected endpoint
 * without a usable authentication.
 */
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    static final String MISSING_MESSAGE = "Authentication is required to access this resource.";
    static final String INVALID_TOKEN_MESSAGE = "Invalid or expired token.";
    static final String ACCOUNT_UNAVAILABLE_MESSAGE = "Account is disabled or locked.";

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String reason = (String) request.getAttribute(SecurityErrorWriter.ERROR_ATTRIBUTE);
        String message;
        if (SecurityErrorWriter.INVALID_TOKEN.equals(reason)) {
            message = INVALID_TOKEN_MESSAGE;
        } else if (SecurityErrorWriter.ACCOUNT_UNAVAILABLE.equals(reason)) {
            message = ACCOUNT_UNAVAILABLE_MESSAGE;
        } else {
            message = MISSING_MESSAGE;
        }

        SecurityErrorWriter.write(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                "UNAUTHORIZED", message, request.getRequestURI());
    }
}
