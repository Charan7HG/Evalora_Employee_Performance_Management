package com.evalora.backend.repository;

import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapping tests for the users table.
 *
 * <p>Case-insensitive email uniqueness lives in PostgreSQL as the functional
 * unique index {@code ux_users_email_lower} on {@code LOWER(email)}. JPA cannot
 * declare a functional index, and the H2 test schema is generated from the
 * entities, so that index does not exist here and cannot be asserted in this
 * class. It is verified against the real schema, and the application guarantees
 * it by trimming and lowercasing every email before insert or lookup (Phase 3B).</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    private User newUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("$2a$12$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOP");
        user.setRole(role);
        return user;
    }

    @Test
    void savesUserAndAppliesDefaults() {
        User saved = userRepository.saveAndFlush(newUser("hr@evalora.local", Role.HR));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getFailedLoginAttempts()).isZero();
        assertThat(saved.getLockedUntil()).isNull();
    }

    @Test
    void roleIsStoredAsTextAndReadBackCorrectly() {
        userRepository.saveAndFlush(newUser("manager@evalora.local", Role.MANAGER));

        User reloaded = userRepository.findByEmailIgnoreCase("manager@evalora.local").orElseThrow();

        assertThat(reloaded.getRole()).isEqualTo(Role.MANAGER);
    }

    @Test
    void findsUserByEmailIgnoringCase() {
        userRepository.saveAndFlush(newUser("employee@evalora.local", Role.EMPLOYEE));

        assertThat(userRepository.findByEmailIgnoreCase("EMPLOYEE@Evalora.Local")).isPresent();
        assertThat(userRepository.existsByEmailIgnoreCase("Employee@EVALORA.local")).isTrue();
    }

    @Test
    void lockoutStateIsPersisted() {
        User user = newUser("hr@evalora.local", Role.HR);
        OffsetDateTime lockedUntil = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5);
        user.setFailedLoginAttempts(5);
        user.setLockedUntil(lockedUntil);
        userRepository.saveAndFlush(user);

        User reloaded = userRepository.findByEmailIgnoreCase("hr@evalora.local").orElseThrow();

        assertThat(reloaded.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(reloaded.getLockedUntil().toInstant()).isEqualTo(lockedUntil.toInstant());
    }
}
