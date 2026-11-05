package com.evalora.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Returns 403 for an authenticated user who is not allowed to perform the
 * action. Role-based rules are added in later phases; this handler already
 * defines the response format so it never changes later.
 */
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    static final String FORBIDDEN_MESSAGE = "You do not have permission to access this resource.";

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        SecurityErrorWriter.write(response, objectMapper, HttpServletResponse.SC_FORBIDDEN,
                "FORBIDDEN", FORBIDDEN_MESSAGE, request.getRequestURI());
    }
}
