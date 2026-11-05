package com.evalora.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Login payload. Only email and password - never a role.
 */
public record LoginRequest(
        @NotBlank(message = "Email is required.")
        String email,

        @NotBlank(message = "Password is required.")
        String password) {
}
