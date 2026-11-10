package com.evalora.backend.service;

/**
 * Signals that an email could not be handed to the configured provider.
 *
 * <p>The message is intentionally generic: callers may log it, but must never
 * log the cause's message. Provider responses can contain recipient addresses
 * or SMTP diagnostics and must not end up in application logs.</p>
 */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message) {
        super(message);
    }

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
