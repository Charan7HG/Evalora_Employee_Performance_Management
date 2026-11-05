package com.evalora.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Reset-password payload. The token is the raw value returned by the
 * development-only forgot-password response.
 */
public record ResetPasswordRequest(
        @NotBlank(message = "Reset token is required.")
        String token,

        @NotBlank(message = "New password is required.")
        String newPassword,

        @NotBlank(message = "Password confirmation is required.")
        String confirmPassword) {
}
