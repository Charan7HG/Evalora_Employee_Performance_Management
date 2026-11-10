package com.evalora.backend.service;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Gmail SMTP provider. The mail sender is a mock, so these
 * tests never open a network connection or send a real message.
 */
class GmailEmailServiceTest {

    private static final String RECIPIENT = "asha.rao@evalora.local";
    private static final String FROM_ADDRESS = "evalora.notifications@gmail.com";
    private static final String FROM_NAME = "Evalora Notifications";
    private static final String OTP = "123456";
    private static final String ACTIVATION_URL =
            "https://app.evalora.local/activate?token=abc-123";

    private JavaMailSender mailSender;
    private MockEnvironment environment;
    private GmailEmailService service;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(
                invocation -> new MimeMessage(Session.getInstance(new Properties())));
        environment = new MockEnvironment()
                .withProperty("EVALORA_EMAIL_ENABLED", "true")
                .withProperty("EVALORA_MAIL_FROM_ADDRESS", FROM_ADDRESS)
                .withProperty("EVALORA_MAIL_FROM_NAME", FROM_NAME);
        service = new GmailEmailService(mailSender, environment);
    }

    @Test
    void activationEmailUsesTheConfiguredSenderAndBothBodyFormats() throws Exception {
        service.sendActivationEmail(RECIPIENT, "Asha Rao", 17L, ACTIVATION_URL, "activation");

        MimeMessage message = captureSentMessage();
        assertThat(message.getFrom()).hasSize(1);
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo(FROM_ADDRESS);
        assertThat(from.getPersonal()).isEqualTo(FROM_NAME);

        assertThat(message.getAllRecipients()).hasSize(1);
        assertThat(((InternetAddress) message.getAllRecipients()[0]).getAddress())
                .isEqualTo(RECIPIENT);
        assertThat(message.getSubject()).isEqualTo("Activate your Evalora account");
        assertThat(message.getContentType()).startsWith("multipart/alternative");

        String text = partContent(message, "text/plain");
        assertThat(text)
                .contains("Hello Asha Rao,")
                .contains(ACTIVATION_URL)
                .contains("This activation link expires in 24 hours and can be used once.")
                .contains("Contact your HR administrator")
                .contains("Evalora - Performance, with Purpose");

        String html = partContent(message, "text/html");
        assertThat(html)
                .contains("Hello Asha Rao,")
                .contains("href=\"" + ACTIVATION_URL + "\"")
                .contains("This activation link expires in 24 hours and can be used once.")
                .contains("Evalora - Performance, with Purpose");
        assertThat(html).doesNotContain("null");
    }

    @Test
    void otpEmailUsesTheOtpSubjectAndBothBodyFormats() throws Exception {
        service.sendOtpEmail(RECIPIENT, "Asha Rao", OTP);

        MimeMessage message = captureSentMessage();
        assertThat(((InternetAddress) message.getAllRecipients()[0]).getAddress())
                .isEqualTo(RECIPIENT);
        assertThat(message.getSubject()).isEqualTo("Your Evalora OTP code");

        String text = partContent(message, "text/plain");
        assertThat(text)
                .contains("Hello Asha Rao,")
                .contains(OTP)
                .contains("expires in 10 minutes")
                .contains("Do not share it with anyone.");

        String html = partContent(message, "text/html");
        assertThat(html)
                .contains(OTP)
                .contains("expires in 10 minutes")
                .contains("Do not share it with anyone.");
    }

    @Test
    void htmlEscapesTheRecipientName() throws Exception {
        service.sendActivationEmail(RECIPIENT, "Asha <b>Rao</b> & Co", 17L,
                ACTIVATION_URL, "activation");

        String html = partContent(captureSentMessage(), "text/html");
        assertThat(html)
                .contains("Asha &lt;b&gt;Rao&lt;/b&gt; &amp; Co")
                .doesNotContain("Asha <b>Rao</b>");
    }

    @Test
    void blankOrMalformedRecipientsAreRejectedBeforeSmtpIsUsed() {
        assertThatThrownBy(() -> service.sendOtpEmail("  ", "Asha", OTP))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessage("Recipient email address is required.");
        assertThatThrownBy(() -> service.sendOtpEmail("not-an-email", "Asha", OTP))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessage("Recipient email address is invalid.");
        assertThatThrownBy(() -> service.sendOtpEmail(null, "Asha", OTP))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessage("Recipient email address is required.");

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void nonHttpActivationUrlsAreRejectedBeforeSmtpIsUsed() {
        assertThatThrownBy(() -> service.sendActivationEmail(
                RECIPIENT, "Asha", 17L, "javascript:alert(1)", "activation"))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessage("Activation link is not an absolute HTTP(S) URL.");

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void smtpFailureIsSurfacedWithoutLeakingProviderDetailsOrTheOtp() {
        doThrow(new MailSendException("535-5.7.8 Username and Password not accepted"))
                .when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> service.sendOtpEmail(RECIPIENT, "Asha", OTP))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessage("Gmail SMTP delivery failed.")
                .hasMessageNotContaining("535")
                .hasMessageNotContaining(OTP)
                .hasCauseInstanceOf(MailSendException.class);
    }

    @Test
    void emailDisabledMeansNothingIsSent() {
        environment.setProperty("EVALORA_EMAIL_ENABLED", "false");
        GmailEmailService disabledService = new GmailEmailService(mailSender, environment);

        assertThat(disabledService.isEmailEnabled()).isFalse();
        disabledService.sendActivationEmail(RECIPIENT, "Asha", 17L,
                ACTIVATION_URL, "activation");
        disabledService.sendOtpEmail(RECIPIENT, "Asha", OTP);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    private MimeMessage captureSentMessage() throws Exception {
        ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(message.capture());
        MimeMessage captured = message.getValue();
        // A real transport commits headers on write; the mock does not.
        captured.saveChanges();
        return captured;
    }

    private static String partContent(MimeMessage message, String contentType) throws Exception {
        String content = findPart(message.getContent(), contentType);
        if (content == null) {
            throw new AssertionError("Missing " + contentType + " part.");
        }
        return content;
    }

    private static String findPart(Object content, String contentType) throws Exception {
        if (!(content instanceof Multipart multipart)) {
            return null;
        }
        for (int index = 0; index < multipart.getCount(); index++) {
            Part part = multipart.getBodyPart(index);
            if (part.getContentType().toLowerCase().startsWith(contentType)) {
                return part.getContent().toString();
            }
            String nested = findPart(part.getContent(), contentType);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
}
