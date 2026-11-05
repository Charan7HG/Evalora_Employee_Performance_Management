package com.evalora.backend.util;

import java.util.Locale;

/**
 * One single place where an email is normalised.
 *
 * <p>The database stores emails in lowercase and its case-insensitive
 * uniqueness is the functional index {@code ux_users_email_lower}. If every
 * service normalises with the same two calls, that index behaves exactly like
 * a plain unique index and lookups are predictable.</p>
 *
 * <p>Planned entry points (later phases): login, account creation, password
 * reset. Never write a local {@code toLowerCase()} call somewhere else.</p>
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    /**
     * @param email raw value from a request or a token subject
     * @return trimmed, lowercased email
     * @throws IllegalArgumentException when the value is null
     */
    public static String normalize(String email) {
        if (email == null) {
            throw new IllegalArgumentException("Email must not be null.");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
