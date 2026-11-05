package com.evalora.backend.security;

import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.List;

/**
 * The authenticated user for one request, built from the database row - never
 * from JWT claims.
 *
 * <p>Only immutable, non-secret data is carried: id, email, role, enabled and
 * whether the account is currently locked. No password hash.</p>
 */
@Getter
public class EvaloraPrincipal implements UserDetails {

    private final Long userId;
    private final String email;
    private final Role role;
    private final boolean enabled;
    private final boolean accountNonLocked;
    private final List<GrantedAuthority> authorities;

    private EvaloraPrincipal(Long userId, String email, Role role, boolean enabled, boolean accountNonLocked) {
        this.userId = userId;
        this.email = email;
        this.role = role;
        this.enabled = enabled;
        this.accountNonLocked = accountNonLocked;
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    /** Builds the principal from the current database state of the account. */
    public static EvaloraPrincipal from(User user) {
        boolean locked = user.getLockedUntil() != null
                && user.getLockedUntil().toInstant().isAfter(Instant.now());
        return new EvaloraPrincipal(user.getId(), user.getEmail(), user.getRole(),
                user.isEnabled(), !locked);
    }

    @Override
    public String getUsername() {
        return email;
    }

    /** Credentials are never carried in the security context. */
    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
