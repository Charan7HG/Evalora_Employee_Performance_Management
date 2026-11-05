package com.evalora.backend.dto;

/**
 * Generic forgot-password response used outside the {@code dev} profile. It
 * deliberately reveals nothing about whether the email exists.
 */
public record ForgotPasswordResponse(String message) {
}
