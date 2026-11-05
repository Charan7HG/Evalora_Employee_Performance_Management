package com.evalora.backend.security;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Minimal, stable body for security errors. No stack traces, no exception
 * messages, no token contents.
 *
 * <p>{@code timestamp} is an ISO-8601 UTC string so the body never depends on
 * the JSON date configuration.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SecurityErrorResponse(String timestamp,
                                    int status,
                                    String error,
                                    String message,
                                    String path,
                                    Long lockedUntilEpochSeconds,
                                    Long retryAfterSeconds) {
    public SecurityErrorResponse(String timestamp, int status, String error, String message, String path) {
        this(timestamp, status, error, message, path, null, null);
    }
}
