package com.evalora.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Activation payload. The token comes from the single-use activation email;
 * the password is chosen by the employee on the activation page and is never
 * accepted from HR or stored in plaintext.
 */
public record ActivateAccountRequest(
        @NotBlank(message = "Activation token is required.")
        String token,

        @NotBlank(message = "New password is required.")
        String newPassword,

        @NotBlank(message = "Password confirmation is required.")
        String confirmPassword) {
}
