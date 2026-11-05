package com.evalora.backend.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Generates reset tokens and hashes them for storage.
 *
 * <p>A token is 32 bytes from {@link SecureRandom}, rendered as 64 hex
 * characters (256 bits of entropy). Only its SHA-256 hash is stored, so the
 * database never contains a usable token.</p>
 */
public final class PasswordResetTokenUtil {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final HexFormat HEX = HexFormat.of();

    private PasswordResetTokenUtil() {
    }

    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    public static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is required by the JDK; this can only mean a broken runtime.
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }
}
