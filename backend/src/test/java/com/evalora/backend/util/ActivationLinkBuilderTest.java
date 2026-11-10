package com.evalora.backend.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationLinkBuilderTest {

    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void buildsTheActivationLinkFromTheConfiguredBaseUrl() {
        assertThat(ActivationLinkBuilder.build("http://localhost:5173", TOKEN))
                .isEqualTo("http://localhost:5173/activate?token=" + TOKEN);
    }

    @Test
    void preservesALanAddressAndPortForMobileTesting() {
        assertThat(ActivationLinkBuilder.build("http://192.168.1.20:5173", TOKEN))
                .isEqualTo("http://192.168.1.20:5173/activate?token=" + TOKEN);
    }

    @Test
    void preservesAContextPathInsteadOfDroppingIt() {
        assertThat(ActivationLinkBuilder.build("https://example.com/app", TOKEN))
                .isEqualTo("https://example.com/app/activate?token=" + TOKEN);
    }

    @Test
    void ignoresTrailingSlashesAndAnyQueryOrFragmentOnTheBaseUrl() {
        assertThat(ActivationLinkBuilder.build("https://example.com/app/?debug=1#top", TOKEN))
                .isEqualTo("https://example.com/app/activate?token=" + TOKEN);
        assertThat(ActivationLinkBuilder.build("https://example.com/", TOKEN))
                .isEqualTo("https://example.com/activate?token=" + TOKEN);
    }

    @Test
    void rejectsNonHttpAndRelativeBaseUrls() {
        assertThatThrownBy(() -> ActivationLinkBuilder.build("ftp://example.com", TOKEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP(S)");
        assertThatThrownBy(() -> ActivationLinkBuilder.build("localhost:5173", TOKEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP(S)");
    }

    @Test
    void rejectsMissingSettings() {
        assertThatThrownBy(() -> ActivationLinkBuilder.build(null, TOKEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActivationLinkBuilder.build(" ", TOKEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActivationLinkBuilder.build("https://example.com", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActivationLinkBuilder.build("https://example.com", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidConfiguredFrontendBaseUrl() {
        assertThatThrownBy(() -> ActivationLinkBuilder.validateBaseUrl("not a url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed");
        assertThatThrownBy(() -> ActivationLinkBuilder.validateBaseUrl("file:///tmp/app"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute HTTP(S)");
    }
}
