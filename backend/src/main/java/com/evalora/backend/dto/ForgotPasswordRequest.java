package com.evalora.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Forgot-password payload. The response is identical whether or not the email
 * belongs to an account.
 */
public record ForgotPasswordRequest(
        @NotBlank(message = "Email is required.")
        String email) {
}
