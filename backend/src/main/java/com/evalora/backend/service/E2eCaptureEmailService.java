package com.evalora.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@Profile("e2e-capture & !dev & !prod")
public class E2eCaptureEmailService implements EmailService {

    private static final int MAX_MESSAGES = 100;
    private static final long MAX_TOTAL_BYTES = 256 * 1024;
    private static final String MESSAGE_FILE_SUFFIX = ".json";

    private final Path directory;
    private final ObjectMapper objectMapper;
    private int messageCount;
    private long storedBytes;

    public E2eCaptureEmailService(
            @Value("${evalora.e2e.capture.directory}") String directory,
            ObjectMapper objectMapper) {
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException("E2E email capture directory must be configured.");
        }
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        initializeStorage();
    }

    @Override
    public boolean isEmailEnabled() {
        return true;
    }

    @Override
    public void sendActivationEmail(String recipientEmail, String recipientName,
                                    Long userId, String activationUrl, String templateType) {
        capture(new CapturedEmail("activation", requireRecipient(recipientEmail), userId,
                requireContent(activationUrl)));
    }

    @Override
    public void sendOtpEmail(String recipientEmail, String recipientName, String otp) {
        capture(new CapturedEmail("otp", requireRecipient(recipientEmail), null,
                requireContent(otp)));
    }

    private synchronized void capture(CapturedEmail message) {
        if (messageCount >= MAX_MESSAGES) {
            throw new IllegalStateException("E2E email capture limit reached.");
        }
        try {
            byte[] json = objectMapper.writeValueAsBytes(message);
            if (storedBytes + json.length > MAX_TOTAL_BYTES) {
                throw new IllegalStateException("E2E email capture storage limit reached.");
            }

            Path file = directory.resolve(UUID.randomUUID() + MESSAGE_FILE_SUFFIX);
            Files.write(file, json, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                setPrivatePermissions(file, false);
            } catch (IOException ex) {
                Files.deleteIfExists(file);
                throw ex;
            }
            messageCount++;
            storedBytes += json.length;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to capture E2E email.", ex);
        }
    }

    private void initializeStorage() {
        try {
            Path parent = directory.getParent();
            if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("E2E email capture parent directory must already exist.");
            }
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(directory)) {
                throw new IllegalStateException("E2E email capture directory must not be a symbolic link.");
            }
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                        || !hasPrivatePermissions(directory, true)) {
                    throw new IllegalStateException("Existing E2E email capture directory is not private.");
                }
            } else {
                createPrivateDirectory(directory);
            }

            try (Stream<Path> entries = Files.list(directory)) {
                for (Path entry : entries.toList()) {
                    if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(entry)
                            || !hasPrivatePermissions(entry, false)
                            || !entry.getFileName().toString().matches(
                            "[0-9a-fA-F-]{36}" + MESSAGE_FILE_SUFFIX)) {
                        throw new IllegalStateException("E2E email capture directory contains an unexpected entry.");
                    }
                    messageCount++;
                    storedBytes += Files.size(entry);
                }
            }
            if (messageCount > MAX_MESSAGES || storedBytes > MAX_TOTAL_BYTES) {
                throw new IllegalStateException("E2E email capture storage exceeds its configured limits.");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to initialize E2E email capture storage.", ex);
        }
    }

    private static void createPrivateDirectory(Path directory) throws IOException {
        PosixFileAttributeView parentPosixView = Files.getFileAttributeView(
                directory.getParent(), PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (parentPosixView != null) {
            Files.createDirectory(directory,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            return;
        }

        Files.createDirectory(directory);
        try {
            setPrivatePermissions(directory, true);
        } catch (IOException ex) {
            Files.deleteIfExists(directory);
            throw ex;
        }
    }

    private static boolean hasPrivatePermissions(Path path, boolean directory) throws IOException {
        PosixFileAttributeView posixView = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posixView != null) {
            return Files.getPosixFilePermissions(path).equals(
                    PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        }

        AclFileAttributeView aclView = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (aclView == null) {
            return false;
        }
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        List<AclEntry> acl = aclView.getAcl();
        return !acl.isEmpty() && acl.stream().allMatch(entry ->
                entry.type() == AclEntryType.ALLOW && entry.principal().equals(owner));
    }

    private static void setPrivatePermissions(Path path, boolean directory) throws IOException {
        PosixFileAttributeView posixView = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posixView != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }

        AclFileAttributeView aclView = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (aclView == null) {
            throw new IOException("Private permissions are not supported for E2E capture storage.");
        }

        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        AclEntry ownerOnly = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        aclView.setAcl(List.of(ownerOnly));
    }

    private static String requireRecipient(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException("Captured email recipient is required.");
        }
        return recipient;
    }

    private static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Captured email content is required.");
        }
        return content;
    }
}
