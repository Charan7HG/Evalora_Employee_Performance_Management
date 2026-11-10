package com.evalora.backend.dto;

public record ActivationStatusResponse(boolean pending, boolean enabled, String email) {
}
