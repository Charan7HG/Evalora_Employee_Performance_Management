package com.evalora.backend.service;

/**
 * Outcome of a forgot-password request.
 *
 * @param message    the same generic message for every email
 * @param resetToken the raw token, present only when the {@code dev} profile is active
 */
public record ForgotPasswordResult(String message, String resetToken) {
}
