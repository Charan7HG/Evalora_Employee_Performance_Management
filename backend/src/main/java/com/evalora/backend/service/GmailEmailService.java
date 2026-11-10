package com.evalora.backend.service;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Gmail SMTP delivery for production.
 *
 * <p>Active only when the {@code gmail} profile is enabled and the E2E capture
 * profile is not ({@code gmail & !e2e-capture}). The SMTP connection settings
 * live in {@code application.properties} and are read from
 * {@code EVALORA_SMTP_*} environment variables, so no credential is compiled
 * into the application.</p>
 *
 * <p>Failures are surfaced as {@link EmailDeliveryException}. There is no
 * fallback to another provider: a rejected App Password must fail loudly
 * instead of silently reporting success.</p>
 */
@Service
@Profile("gmail & !e2e-capture")
public class GmailEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(GmailEmailService.class);

    private final JavaMailSender mailSender;
    private final EmailSettings settings;

    public GmailEmailService(JavaMailSender mailSender, Environment environment) {
        this.mailSender = mailSender;
        this.settings = EmailSettings.from(environment);
    }

    @Override
    public boolean isEmailEnabled() {
        return settings.enabled();
    }

    @Override
    public void sendActivationEmail(String recipientEmail, String recipientName,
                                    Long userId, String activationUrl, String templateType) {
        if (!settings.enabled()) {
            log.debug("Email sending disabled; skipping activation email.");
            return;
        }

        String url = EmailTemplates.requireValidActivationUrl(activationUrl);
        send(recipientEmail, recipientName,
                EmailTemplates.activationSubject(templateType),
                EmailTemplates.activationHtml(recipientName, url, recipientEmail),
                EmailTemplates.activationText(recipientName, url, recipientEmail));
    }

    @Override
    public void sendOtpEmail(String recipientEmail, String recipientName, String otp) {
        if (!settings.enabled()) {
            log.debug("Email sending disabled; skipping OTP email.");
            return;
        }

        send(recipientEmail, recipientName,
                "Your Evalora OTP code",
                EmailTemplates.otpHtml(recipientName, otp),
                EmailTemplates.otpText(recipientName, otp));
    }

    private void send(String recipientEmail, String recipientName, String subject,
                      String htmlContent, String plainTextContent) {
        String recipient = EmailTemplates.requireValidRecipientEmail(recipientEmail);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, true, StandardCharsets.UTF_8.name());

            helper.setFrom(new InternetAddress(settings.fromAddress(), settings.fromName(),
                    StandardCharsets.UTF_8.name()));

            if (recipientName == null || recipientName.isBlank()) {
                helper.setTo(recipient);
            } else {
                helper.setTo(new InternetAddress(recipient, recipientName.trim(),
                        StandardCharsets.UTF_8.name()));
            }

            helper.setSubject(subject);

            // Transactional-message hints. They do not override authentication
            // (SPF/DKIM/DMARC), but they tell clients this is not a bulk mailing.
            message.setHeader("Auto-Submitted", "auto-generated");
            message.setHeader("X-Auto-Response-Suppress", "All");

            // Build the multipart/alternative body explicitly. Spring 7's
            // MimeMessageHelper.setText(plain, html) wraps the alternative body
            // in an extra part, so construct the two alternatives directly.
            MimeMultipart alternative = new MimeMultipart("alternative");

            MimeBodyPart plainPart = new MimeBodyPart();
            plainPart.setText(plainTextContent, StandardCharsets.UTF_8.name(), "plain");
            alternative.addBodyPart(plainPart);

            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setText(htmlContent, StandardCharsets.UTF_8.name(), "html");
            alternative.addBodyPart(htmlPart);

            message.setContent(alternative);

            mailSender.send(message);
        } catch (EmailDeliveryException ex) {
            throw ex;
        } catch (Exception ex) {
            // Deliberately do not include ex.getMessage(): provider responses can
            // contain the recipient address or SMTP diagnostics. The cause is
            // retained for local debugging but never logged by the callers.
            throw new EmailDeliveryException("Gmail SMTP delivery failed.", ex);
        }
    }
}
