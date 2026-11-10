
package com.evalora.backend.service;

import org.springframework.core.env.Environment;

/**
 * Common, non-secret settings shared by every email provider.
 */
record EmailSettings(boolean enabled, String fromAddress, String fromName) {

    static final String DEFAULT_FROM_ADDRESS = "evalora@evalora.local";
    static final String DEFAULT_FROM_NAME = "Evalora";

    static EmailSettings from(Environment environment) {

        String fromAddress = firstNonBlank(
                environment.getProperty("EVALORA_MAIL_FROM_ADDRESS"),
                environment.getProperty("evalora.mail.from-address"));

        String fromName = firstNonBlank(
                environment.getProperty("EVALORA_MAIL_FROM_NAME"),
                environment.getProperty("evalora.mail.from-name"));

        return new EmailSettings(
                isEnabled(environment),
                fromAddress != null ? fromAddress : DEFAULT_FROM_ADDRESS,
                fromName != null ? fromName : DEFAULT_FROM_NAME);
    }

    private static boolean isEnabled(Environment environment) {
        String value = environment.getProperty("EVALORA_EMAIL_ENABLED");

        if (value == null) {
            value = environment.getProperty("evalora.email.enabled");
        }

        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return null;
    }
}
