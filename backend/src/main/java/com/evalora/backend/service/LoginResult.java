package com.evalora.backend.service;

import com.evalora.backend.dto.LoginResponse;

/**
 * Result of a login attempt.
 *
 * <p>The service returns a result instead of throwing for bad credentials on
 * purpose: the failed-attempt counter must be committed even when the login is
 * rejected. A runtime exception inside a transaction would roll that update
 * back.</p>
 *
 * <p>{@code retryAfterSeconds} is only meaningful for
 * {@link LoginFailure#ACCOUNT_LOCKED}: it is the server-computed remainder of
 * the five-minute lock, so the browser counts down the real interval instead of
 * guessing.</p>
 */
public record LoginResult(LoginResponse response, LoginFailure failure, long retryAfterSeconds) {

    public static LoginResult success(LoginResponse response) {
        return new LoginResult(response, null, 0);
    }

    public static LoginResult failure(LoginFailure failure) {
        return new LoginResult(null, failure, 0);
    }

    /** A locked account, with the remaining lock time in whole seconds (at least 1). */
    public static LoginResult locked(long retryAfterSeconds) {
        return new LoginResult(null, LoginFailure.ACCOUNT_LOCKED, Math.max(1, retryAfterSeconds));
    }

    public boolean isSuccess() {
        return failure == null;
    }
}
