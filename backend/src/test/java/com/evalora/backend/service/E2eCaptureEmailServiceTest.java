package com.evalora.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class E2eCaptureEmailServiceTest {

    @TempDir
    Path tempDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void storesOnlyActivationAndOtpMessagesInPrivateFiles() throws IOException {
        Path captureDirectory = tempDirectory.resolve("messages");
        E2eCaptureEmailService service = new E2eCaptureEmailService(
                captureDirectory.toString(), objectMapper);
        service.sendActivationEmail("employee@example.test", null, 17L,
                "http://localhost:15173/activate?token=activation-test-value", "activation");
        service.sendOtpEmail("employee@example.test", null, "123456");

        List<CapturedEmail> captures;
        try (Stream<Path> files = Files.list(captureDirectory)) {
            captures = files.map(this::readCapture).toList();
        }

        assertThat(captures).hasSize(2);
        assertThat(captures.stream().anyMatch(message ->
                message.type().equals("activation")
                        && message.recipient().equals("employee@example.test")
                        && Long.valueOf(17L).equals(message.accountId())
                        && message.content().startsWith("http://localhost:15173/activate?token=")))
                .isTrue();
        assertThat(captures.stream().anyMatch(message ->
                message.type().equals("otp")
                        && message.recipient().equals("employee@example.test")
                        && message.accountId() == null
                        && message.content().matches("\\d{6}")))
                .isTrue();

        if (Files.getFileAttributeView(captureDirectory, PosixFileAttributeView.class) != null) {
            assertThat(Files.getPosixFilePermissions(captureDirectory))
                    .isEqualTo(PosixFilePermissions.fromString("rwx------"));
            try (Stream<Path> files = Files.list(captureDirectory)) {
                assertThat(files.allMatch(file -> {
                    try {
                        return Files.getPosixFilePermissions(file)
                                .equals(PosixFilePermissions.fromString("rw-------"));
                    } catch (IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                })).isTrue();
            }
        }
    }

    @Test
    void rejectsCaptureAfterTheMessageBoundIsReached() {
        E2eCaptureEmailService service = new E2eCaptureEmailService(
                tempDirectory.resolve("bounded").toString(), objectMapper);
        for (int index = 0; index < 100; index++) {
            service.sendOtpEmail("employee" + index + "@example.test", null, "123456");
        }

        assertThatThrownBy(() -> service.sendOtpEmail("employee@example.test", null, "123456"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("E2E email capture limit reached.");
    }

    private CapturedEmail readCapture(Path file) {
        try {
            return objectMapper.readValue(Files.readAllBytes(file), CapturedEmail.class);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
