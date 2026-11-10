package com.evalora.backend.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * Shared email content for every provider.
 *
 * <p>Keeping the templates here guarantees that Brevo and Gmail send the same
 * branding, the same expiry wording, and above all the same escaping rules.
 * All user-controlled values are HTML-escaped, the activation URL is validated
 * before it is embedded, and every message has a plain-text alternative.</p>
 */
final class EmailTemplates {

    private static final Pattern RECIPIENT_EMAIL = Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@"
                    + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");

    private static final int MAX_EMAIL_LENGTH = 254;

    private static final String ACTIVATION_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>Activate your Evalora account</title>
            </head>
            <body style="margin:0;padding:0;background-color:#f5f8fc;font-family:Arial,Helvetica,sans-serif;color:#111827;">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#f5f8fc;padding:24px 12px;">
                    <tr>
                        <td align="center">
                            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:520px;background-color:#ffffff;border:1px solid #e5e7eb;border-radius:8px;padding:32px;">
                                <tr>
                                    <td>
                                        <h1 style="margin:0 0 16px;font-size:22px;color:#111827;">Activate your Evalora account</h1>
                                        <p style="margin:0 0 12px;font-size:16px;">Hello {{name}},</p>
                                        <p style="margin:0 0 12px;font-size:16px;">Your HR team created an Evalora account for you. Choose your password to finish setting it up.</p>
                                        <p style="margin:0 0 12px;font-size:16px;">This activation link expires in 24 hours and can be used once.</p>
                                        <p style="margin:24px 0;">
                                            <a href="{{url}}" style="background-color:#4f46e5;color:#ffffff;padding:12px 24px;text-decoration:none;border-radius:6px;font-weight:bold;display:inline-block;">Choose your password</a>
                                        </p>
                                        <p style="margin:0 0 8px;font-size:14px;color:#374151;">If the button does not work, copy this address into your browser:</p>
                                        <p style="margin:0 0 24px;font-size:13px;word-break:break-all;color:#374151;">{{url}}</p>
                                        <p style="margin:0 0 12px;font-size:14px;color:#374151;">If you were not expecting this email, do not click the button. Contact your HR administrator.</p>
                                        <hr style="border:none;border-top:1px solid #e5e7eb;margin:24px 0;" />
                                        <p style="margin:0;font-size:12px;color:#6b7280;">
                                            This message was sent to {{email}} because an Evalora account was created for that address.<br />
                                            Evalora - Performance, with Purpose<br />
                                            This is an automated message. Please do not reply.
                                        </p>
                                    </td>
                                </tr>
                            </table>
                        </td>
                    </tr>
                </table>
            </body>
            </html>
            """;

    private static final String ACTIVATION_TEXT = """
            Activate your Evalora account

            Hello {{name}},
            Your HR team created an Evalora account for you. Choose your password to finish setting it up.

            Open this link to choose your password:
            {{url}}

            This activation link expires in 24 hours and can be used once.
            If you were not expecting this email, do not open the link. Contact your HR administrator.

            This message was sent to {{email}} because an Evalora account was created for that address.
            Evalora - Performance, with Purpose
            This is an automated message. Please do not reply.
            """;

    private static final String OTP_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>Your Evalora OTP code</title>
            </head>
            <body style="margin:0;padding:0;background-color:#f3f4f6;font-family:Arial,Helvetica,sans-serif;color:#111827;">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#f3f4f6;padding:24px 0;">
                    <tr>
                        <td align="center">
                            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:520px;background-color:#ffffff;border-radius:8px;padding:32px;">
                                <tr>
                                    <td>
                                        <h1 style="margin:0 0 16px;font-size:24px;color:#4f46e5;">Evalora Password Reset</h1>
                                        <p style="margin:0 0 12px;font-size:16px;">Hello {{name}},</p>
                                        <p style="margin:0 0 12px;font-size:16px;">Your one-time passcode is:</p>
                                        <p style="margin:16px 0;font-size:32px;letter-spacing:6px;font-weight:bold;color:#4f46e5;">{{otp}}</p>
                                        <p style="margin:0 0 12px;font-size:16px;">This code expires in 10 minutes. Do not share it with anyone.</p>
                                        <p style="margin:0 0 12px;font-size:16px;">If you didn't request this, you can safely ignore this email.</p>
                                        <hr style="border:none;border-top:1px solid #e5e7eb;margin:24px 0;" />
                                        <p style="margin:0;font-size:12px;color:#6b7280;">
                                            Evalora — Performance, with Purpose<br />
                                            This is an automated message. Please do not reply.
                                        </p>
                                    </td>
                                </tr>
                            </table>
                        </td>
                    </tr>
                </table>
            </body>
            </html>
            """;

    private static final String OTP_TEXT = """
            Evalora Password Reset

            Hello {{name}},
            Your one-time passcode is: {{otp}}
            This code expires in 10 minutes. Do not share it with anyone.
            If you didn't request this, you can safely ignore this email.

            Evalora — Performance, with Purpose
            This is an automated message. Please do not reply.
            """;

    private EmailTemplates() {
    }

    static String activationSubject(String templateType) {
        return "activation".equals(templateType)
                ? "Activate your Evalora account"
                : "Your Evalora OTP code";
    }

    static String activationHtml(String recipientName, String activationUrl, String recipientEmail) {
        return ACTIVATION_HTML
                .replace("{{name}}", escapedDisplayName(recipientName))
                .replace("{{url}}", escapeHtml(activationUrl))
                .replace("{{email}}", escapeHtml(recipientEmail == null ? "" : recipientEmail.trim()));
    }

    static String activationText(String recipientName, String activationUrl, String recipientEmail) {
        return ACTIVATION_TEXT
                .replace("{{name}}", displayName(recipientName))
                .replace("{{url}}", activationUrl)
                .replace("{{email}}", recipientEmail == null ? "" : recipientEmail.trim());
    }

    static String otpHtml(String recipientName, String otp) {
        return OTP_HTML
                .replace("{{name}}", escapedDisplayName(recipientName))
                .replace("{{otp}}", escapeHtml(otp));
    }

    static String otpText(String recipientName, String otp) {
        return OTP_TEXT
                .replace("{{name}}", displayName(recipientName))
                .replace("{{otp}}", otp);
    }

    /**
     * Validates a recipient address before it reaches a provider. The value is
     * rejected rather than silently dropped, so a delivery attempt can never
     * look successful when the address is blank or malformed.
     */
    static String requireValidRecipientEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new EmailDeliveryException("Recipient email address is required.");
        }
        String trimmed = email.trim();
        if (trimmed.length() > MAX_EMAIL_LENGTH || !RECIPIENT_EMAIL.matcher(trimmed).matches()) {
            throw new EmailDeliveryException("Recipient email address is invalid.");
        }
        return trimmed;
    }

    /**
     * Validates an activation URL before it is embedded in an HTML attribute.
     * Only absolute HTTP(S) URLs are accepted, which also rules out
     * {@code javascript:} and other unexpected schemes.
     */
    static String requireValidActivationUrl(String activationUrl) {
        if (activationUrl == null || activationUrl.isBlank()) {
            throw new EmailDeliveryException("Activation link is missing.");
        }
        String value = activationUrl.trim();
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            boolean httpScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if (!httpScheme || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new EmailDeliveryException("Activation link is not an absolute HTTP(S) URL.");
            }
            return uri.toString();
        } catch (URISyntaxException ex) {
            throw new EmailDeliveryException("Activation link is malformed.", ex);
        }
    }

    static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String displayName(String recipientName) {
        return recipientName == null || recipientName.isBlank() ? "there" : recipientName.trim();
    }

    private static String escapedDisplayName(String recipientName) {
        return escapeHtml(displayName(recipientName));
    }
}
