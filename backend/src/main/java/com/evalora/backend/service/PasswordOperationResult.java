package com.evalora.backend.service;

/**
 * Result of a password operation. Each feature uses only the values relevant to
 * it; {@link #SUCCESS} is shared.
 */
public enum PasswordOperationResult {
    SUCCESS,
    CURRENT_PASSWORD_INCORRECT,
    CONFIRMATION_MISMATCH,
    PASSWORD_REUSED,
    PASSWORD_POLICY_VIOLATION,
    INVALID_RESET_TOKEN,
    DEV_RESET_NOT_CONFIGURED
}
