package com.evalora.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Change-password payload for the authenticated user. There is deliberately no
 * user id or email: the account is always the one in the SecurityContext.
 */
public record ChangePasswordRequest(
        @NotBlank(message = "Current password is required.")
        String currentPassword,

        @NotBlank(message = "New password is required.")
        String newPassword,

        @NotBlank(message = "Password confirmation is required.")
        String confirmPassword) {
}
