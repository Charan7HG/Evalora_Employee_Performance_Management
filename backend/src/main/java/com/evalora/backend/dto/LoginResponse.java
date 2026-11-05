package com.evalora.backend.dto;

/**
 * Successful login response.
 *
 * @param token     the signed JWT
 * @param tokenType always {@code Bearer}
 * @param expiresIn token lifetime in seconds, taken from the configured expiration
 * @param user      safe account information
 */
public record LoginResponse(String token, String tokenType, long expiresIn, AuthUserResponse user) {
}
