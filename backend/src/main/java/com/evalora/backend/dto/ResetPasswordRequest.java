package com.evalora.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Reset-password payload. The token is returned after successful OTP
 * verification.
 */
public record ResetPasswordRequest(
        @NotBlank(message = "Reset token is required.")
        String token,

        @NotBlank(message = "New password is required.")
        String newPassword,

        @NotBlank(message = "Password confirmation is required.")
        String confirmPassword) {
}
