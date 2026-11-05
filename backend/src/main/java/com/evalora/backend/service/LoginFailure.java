package com.evalora.backend.service;

/**
 * The only two outcomes a failed login may expose.
 *
 * <p>Unknown email, wrong password and disabled account all map to
 * {@link #INVALID_CREDENTIALS} so the response never reveals whether an email
 * exists. Only an actively locked account gets its own response.</p>
 */
public enum LoginFailure {
    INVALID_CREDENTIALS,
    ACCOUNT_LOCKED
}
