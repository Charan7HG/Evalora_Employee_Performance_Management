package com.evalora.backend.dto;

import com.evalora.backend.entity.User;

/**
 * Safe public view of an account. Contains no password hash, no failed-attempt
 * counter and no lockout information.
 *
 * <p>Used both as the {@code user} object of a login response and as the body
 * of {@code GET /api/auth/me}.</p>
 */
public record AuthUserResponse(Long id, String email, String role) {

    public static AuthUserResponse from(User user) {
        return new AuthUserResponse(user.getId(), user.getEmail(), user.getRole().name());
    }
}
