package com.evalora.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The password encoder is created by {@link SecurityConfig}; these tests prove
 * it is BCrypt with strength 12 and that it never returns the raw password.
 */
class PasswordEncoderTest {

    private final SecurityConfig securityConfig = new SecurityConfig();
    private final PasswordEncoder passwordEncoder = securityConfig.passwordEncoder();

    @Test
    void usesBcryptWithStrengthTwelve() {
        assertThat(passwordEncoder).isInstanceOf(BCryptPasswordEncoder.class);

        // BCrypt encodes the cost factor in the hash itself: $2a$12$...
        assertThat(passwordEncoder.encode("Strength@Check1")).startsWith("$2a$12$");
    }

    @Test
    void correctPasswordMatchesItsHash() {
        String rawPassword = "Employee@Evalora2026";

        String hash = passwordEncoder.encode(rawPassword);

        assertThat(passwordEncoder.matches(rawPassword, hash)).isTrue();
    }

    @Test
    void incorrectPasswordDoesNotMatch() {
        String hash = passwordEncoder.encode("Employee@Evalora2026");

        assertThat(passwordEncoder.matches("employee@evalora2026", hash)).isFalse();
        assertThat(passwordEncoder.matches("Wrong@Password1", hash)).isFalse();
    }

    @Test
    void storedValueIsAHashAndNotThePlainPassword() {
        String rawPassword = "Manager@Evalora2026";

        String hash = passwordEncoder.encode(rawPassword);

        assertThat(hash).isNotEqualTo(rawPassword);
        assertThat(hash).startsWith("$2");
        assertThat(hash).hasSize(60);
        assertThat(hash).doesNotContain(rawPassword);
    }

    @Test
    void twoHashesOfTheSamePasswordDifferBecauseOfTheSalt() {
        String rawPassword = "Hr@Evalora2026";

        assertThat(passwordEncoder.encode(rawPassword))
                .isNotEqualTo(passwordEncoder.encode(rawPassword));
    }
}
