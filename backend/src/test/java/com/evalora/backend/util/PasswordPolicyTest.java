package com.evalora.backend.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    @Test
    void acceptsAPasswordThatMeetsEveryRule() {
        assertThat(PasswordPolicy.isValid("Employee@2026")).isTrue();
        assertThat(PasswordPolicy.isValid("Abcdefg1!")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Ab@1",          // shorter than 8
            "12345678@",     // no letter
            "Employee@",     // no number
            "Employee2026",  // no special character
            "        a1"     // whitespace is not a special character
    })
    void rejectsPasswordsThatBreakARule(String password) {
        assertThat(PasswordPolicy.isValid(password)).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(PasswordPolicy.isValid(null)).isFalse();
    }
}
