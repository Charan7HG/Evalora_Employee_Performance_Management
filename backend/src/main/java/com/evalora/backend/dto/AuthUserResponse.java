package com.evalora.backend.dto;

import com.evalora.backend.entity.User;

/**
 * Safe public view of an account. Contains no password hash, no failed-attempt
 * counter and no lockout information.
 *
 * <p>Used both as the {@code user} object of a login response and as the body
 * of {@code GET /api/auth/me}. {@code fullName} is the employee profile name and
 * is {@code null} when the account has no profile or a blank name.</p>
 */
public record AuthUserResponse(Long id, String email, String role, String fullName) {

    public static AuthUserResponse from(User user, String fullName) {
        return new AuthUserResponse(user.getId(), user.getEmail(), user.getRole().name(),
                normalize(fullName));
    }

    public static AuthUserResponse from(User user) {
        return from(user, null);
    }

    private static String normalize(String fullName) {
        if (fullName == null) {
            return null;
        }
        String trimmed = fullName.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
