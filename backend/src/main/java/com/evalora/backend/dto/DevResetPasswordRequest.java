package com.evalora.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Development-only direct password reset. There is deliberately no email or
 * token: the account is chosen by the server from
 * {@code evalora.dev-reset.email}, so a visitor can never reset an arbitrary
 * account. The endpoint exists only while the {@code dev} profile is active.
 */
public record DevResetPasswordRequest(
        @NotBlank(message = "New password is required.")
        String newPassword,

        @NotBlank(message = "Password confirmation is required.")
        String confirmPassword) {
}
