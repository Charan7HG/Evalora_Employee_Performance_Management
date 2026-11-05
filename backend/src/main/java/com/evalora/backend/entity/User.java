package com.evalora.backend.entity;

import com.evalora.backend.entity.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * Login identity. This row is the single source of truth for the user's role
 * and for the account lockout state.
 *
 * <p>Email uniqueness is case-insensitive and is guaranteed by the database
 * itself: the V1 migration creates the functional unique index
 * {@code ux_users_email_lower} on {@code LOWER(email)}. That index cannot be
 * expressed with JPA annotations, so no {@code @UniqueConstraint} is declared
 * here - the entities are validated against the real schema, they do not
 * define it.</p>
 *
 * <p>The application still stores every email already normalised: trimmed and
 * lowercased, in one place, before it is inserted or looked up. Phase 3B's
 * AuthService and account-creation flows must do that normalisation; the index
 * is the last line of defence, not the only one.</p>
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User extends BaseEntity {

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /** BCrypt hash only - the plain password is never stored anywhere. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;
}
