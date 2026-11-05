package com.evalora.backend.util;

/**
 * The one password policy used by change-password and reset-password.
 *
 * <p>Rules: at least 8 characters, at least one letter, at least one number and
 * at least one special (non-alphanumeric, non-whitespace) character.</p>
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;

    public static final String MESSAGE =
            "Password must be at least 8 characters long and contain a letter, a number and a special character.";

    private static final String REGEX =
            "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s]).{" + MIN_LENGTH + ",}$";

    private PasswordPolicy() {
    }

    public static boolean isValid(String password) {
        return password != null && password.matches(REGEX);
    }
}
