package com.evalora.backend.service;

import org.springframework.core.env.Environment;

/**
 * Common, non-secret settings shared by every email provider.
 *
 * <p>Values are read from the environment-style keys used by Docker Compose so
 * that a local {@code .env} file and a container use exactly the same names.
 * The Spring property names remain supported as a fallback for local runs.</p>
 */
record EmailSettings(boolean enabled, String fromAddress, String fromName) {

    static final String DEFAULT_FROM_ADDRESS = "evalora@evalora.local";
    static final String DEFAULT_FROM_NAME = "Evalora";

    static EmailSettings from(Environment environment) {
        return new EmailSettings(
                isEnabled(environment),
                firstNonBlank(DEFAULT_FROM_ADDRESS,
                        environment.getProperty("EVALORA_MAIL_FROM_ADDRESS"),
                        environment.getProperty("evalora.mail.from-address")),
                firstNonBlank(DEFAULT_FROM_NAME,
                        environment.getProperty("EVALORA_MAIL_FROM_NAME"),
                        environment.getProperty("evalora.mail.from-name")));
    }

    private static boolean isEnabled(Environment environment) {
        String value = environment.getProperty("EVALORA_EMAIL_ENABLED");
        if (value == null) {
            value = environment.getProperty("evalora.email.enabled");
        }
        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    private static String firstNonBlank(String fallback, String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return fallback;
    }
}
