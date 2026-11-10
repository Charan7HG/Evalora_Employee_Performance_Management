package com.evalora.backend.dto;

public record VerifyOtpResponse(boolean valid, String ticket, String message) {
}
