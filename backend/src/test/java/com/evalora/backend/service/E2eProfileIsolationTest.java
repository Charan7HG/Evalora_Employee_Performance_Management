package com.evalora.backend.service;

import com.evalora.backend.config.E2eHrSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class E2eProfileIsolationTest {

    @TempDir
    Path tempDirectory;

    private ApplicationContextRunner contextRunner;

    @BeforeEach
    void setUp() {
        contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(ProfileBeans.class)
                .withBean(JavaMailSender.class, () -> Mockito.mock(JavaMailSender.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("evalora.e2e.capture.directory="
                        + tempDirectory.resolve("messages"));
    }

    @Test
    void devUsesBrevo() {
        assertSingleProvider("dev", BrevoEmailService.class);
    }

    @Test
    void prodWithoutGmailUsesBrevo() {
        assertSingleProvider("prod", BrevoEmailService.class);
    }

    @Test
    void prodWithGmailUsesGmail() {
        assertSingleProvider("prod,gmail", GmailEmailService.class);
    }

    @Test
    void gmailAloneUsesGmail() {
        assertSingleProvider("gmail", GmailEmailService.class);
    }

    @Test
    void e2eCaptureKeepsTheCaptureProvider() {
        assertSingleProvider("e2e-capture", E2eCaptureEmailService.class);
    }

    @Test
    void productionCannotCombineWithTheCaptureProfile() {
        contextRunner.withPropertyValues("spring.profiles.active=prod,e2e-capture")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(EmailService.class);
                    assertThat(context).doesNotHaveBean(E2eCaptureEmailService.class);
                    assertThat(context).doesNotHaveBean(E2eHrSeeder.class);
                });
    }

    @Test
    void gmailCannotLeakIntoTheCaptureProfile() {
        // The E2E stack must always use the deterministic file capture provider.
        assertSingleProvider("gmail,e2e-capture", E2eCaptureEmailService.class);
    }

    private void assertSingleProvider(String profiles, Class<? extends EmailService> expected) {
        contextRunner.withPropertyValues("spring.profiles.active=" + profiles)
                .run(context -> {
                    assertThat(context).hasSingleBean(EmailService.class);
                    assertThat(context.getBean(EmailService.class)).isInstanceOf(expected);
                    assertThat(context).doesNotHaveBean(E2eHrSeeder.class);
                    if (expected != E2eCaptureEmailService.class) {
                        assertThat(context).doesNotHaveBean(E2eCaptureEmailService.class);
                    }
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ BrevoEmailService.class, GmailEmailService.class,
            E2eCaptureEmailService.class, E2eHrSeeder.class })
    static class ProfileBeans {
    }
}
