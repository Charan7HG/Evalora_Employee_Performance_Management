package com.evalora.backend.dto;

/**
 * Development-only forgot-password response. The raw token is included so a
 * developer can complete the reset flow locally without an email provider.
 */
public record ForgotPasswordDevResponse(String message, String resetToken) {
}
