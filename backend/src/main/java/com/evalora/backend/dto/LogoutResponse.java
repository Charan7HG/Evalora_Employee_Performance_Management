package com.evalora.backend.dto;

/**
 * Logout response. Logout is stateless: this is only a confirmation that the
 * client should discard its token.
 */
public record LogoutResponse(String message) {
}
