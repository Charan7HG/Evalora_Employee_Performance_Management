package com.evalora.backend.dto;

/** Result of a successful change-password or reset-password call. */
public record PasswordOperationResponse(String message) {
}
