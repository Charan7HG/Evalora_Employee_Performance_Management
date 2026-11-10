package com.evalora.backend.service;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Brevo transactional API delivery.
 *
 * <p>Still the provider for {@code prod} (without {@code gmail}) and for local
 * development. E2E tests keep using the capture provider, and Gmail takes over
 * when the {@code gmail} profile is active.</p>
 *
 * <p>Provider failures are no longer swallowed: they are wrapped in
 * {@link EmailDeliveryException} so callers cannot report a successful delivery
 * when Brevo rejected the request.</p>
 */
@Service
@Profile("!gmail & !e2e-capture")
public class BrevoEmailService implements EmailService {

    private final EmailSettings settings;
    private final RestClient restClient;

    public BrevoEmailService(Environment environment) {
        this.settings = EmailSettings.from(environment);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .baseUrl("https://api.brevo.com/v3/smtp/email")
                .requestFactory(requestFactory)
                .defaultHeader("api-key", environment.getProperty("BREVO_API_KEY", ""))
                .defaultHeader("accept", "application/json")
                .defaultHeader("content-type", "application/json")
                .build();
    }

    @Override
    public boolean isEmailEnabled() {
        return settings.enabled();
    }

    @Override
    public void sendActivationEmail(String recipientEmail, String recipientName,
                                    Long userId, String activationUrl, String templateType) {
        if (!settings.enabled()) {
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

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sender", Map.of("name", settings.fromName(), "email", settings.fromAddress()));
        // Brevo's API expects "to" to be an array of recipient objects.
        body.put("to", List.of(Map.of(
                "email", recipient,
                "name", recipientName == null ? "" : recipientName.trim())));
        body.put("subject", subject);
        body.put("htmlContent", htmlContent);
        body.put("textContent", plainTextContent);

        try {
            restClient.post()
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            // The cause is retained for local debugging but callers only log a
            // generic message, never Brevo's response or the recipient address.
            throw new EmailDeliveryException("Brevo email delivery failed.", ex);
        }
    }
}
