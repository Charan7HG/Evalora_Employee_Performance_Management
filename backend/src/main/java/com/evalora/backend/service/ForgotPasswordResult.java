package com.evalora.backend.service;

/**
 * Outcome of a forgot-password request.
 *
 * @param message the same generic message for every email
 */
public record ForgotPasswordResult(String message) {
}
