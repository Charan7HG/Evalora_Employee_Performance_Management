package com.evalora.backend.util;

import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Builds the single-use activation link that is emailed to a new employee.
 *
 * <p>The frontend origin comes from {@code EVALORA_FRONTEND_URL}
 * ({@code evalora.frontend.base-url}). It must never be hardcoded: a
 * {@code localhost} URL works on the laptop that runs the stack but resolves to
 * the phone itself when the email is opened on a mobile device. A LAN address
 * such as {@code http://192.168.x.x:5173} or a public HTTPS origin is supplied
 * through that variable instead.</p>
 *
 * <p>A path prefix on the base URL is preserved in the generated link. The
 * current React application is root-mounted, so path-prefixed hosting is not
 * supported until the frontend router and web server are configured for it.</p>
 */
public final class ActivationLinkBuilder {

    private ActivationLinkBuilder() {
    }

    public static String build(String frontendBaseUrl, String rawToken) {
        if (frontendBaseUrl == null || frontendBaseUrl.isBlank()
                || rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Activation link settings are required.");
        }

        URI base = validateBaseUrl(frontendBaseUrl);

        String path = base.getPath() == null ? "" : base.getPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        return UriComponentsBuilder.fromUri(base)
                .replacePath(path + "/activate")
                .replaceQuery(null)
                .fragment(null)
                .queryParam("token", rawToken)
                .build()
                .encode()
                .toUriString();
    }

    public static URI validateBaseUrl(String frontendBaseUrl) {
        if (frontendBaseUrl == null || frontendBaseUrl.isBlank()) {
            throw new IllegalArgumentException("Frontend base URL is required.");
        }

        String trimmedBase = frontendBaseUrl.trim();
        URI base;
        try {
            base = URI.create(trimmedBase);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Frontend base URL is malformed.", ex);
        }

        String scheme = base.getScheme();
        boolean httpScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!httpScheme || base.getHost() == null || base.getHost().isBlank()) {
            throw new IllegalArgumentException("Frontend base URL must be an absolute HTTP(S) URL.");
        }
        return base;
    }
}
