package com.evalora.backend.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordResetTokenUtilTest {

    @Test
    void generatesA256BitHexToken() {
        String token = PasswordResetTokenUtil.generate();

        assertThat(token).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void generatesDifferentTokens() {
        assertThat(PasswordResetTokenUtil.generate())
                .isNotEqualTo(PasswordResetTokenUtil.generate());
    }

    @Test
    void hashesWithSha256AsHex() {
        // Known SHA-256 of "abc".
        assertThat(PasswordResetTokenUtil.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void hashIsDeterministicAndNeverTheRawValue() {
        String token = PasswordResetTokenUtil.generate();

        assertThat(PasswordResetTokenUtil.hash(token))
                .hasSize(64)
                .isEqualTo(PasswordResetTokenUtil.hash(token))
                .isNotEqualTo(token);
    }
}
