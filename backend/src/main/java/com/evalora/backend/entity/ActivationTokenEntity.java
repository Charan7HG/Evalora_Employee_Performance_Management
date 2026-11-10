package com.evalora.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * One-time activation token for new employee/manager accounts.
 * Only the SHA-256 hash of the token is stored, so a leaked database cannot
 * be used to activate accounts.
 *
 * <p>Tokens expire after 24 hours and are single-use only.</p>
 */
@Entity
@Table(name = "activation_tokens",
        uniqueConstraints = @UniqueConstraint(name = "uq_activation_token_hash", columnNames = "token_hash"))
@Getter
@Setter
@NoArgsConstructor
public class ActivationTokenEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** SHA-256 hash of the raw activation token. Never stored in plaintext. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** Expiration time for the activation token (24 hours after creation). */
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;
}