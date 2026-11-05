package com.evalora.backend.repository;

import com.evalora.backend.entity.PasswordResetToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    List<PasswordResetToken> findByUser_IdAndUsedAtIsNull(Long userId);

    /**
     * Reads only the owner id, without loading the token entity.
     *
     * <p>Password reset needs the user row before the token row so that all
     * reset and forgot-password transactions acquire locks in the same order.
     * A scalar projection avoids putting a stale token in the persistence
     * context before the write lock is taken.</p>
     */
    @Query("select t.user.id from PasswordResetToken t where t.tokenHash = :tokenHash")
    Optional<Long> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * Locks the token row for the rest of the transaction. This is what makes a
     * reset token strictly one-time: a second concurrent request waits here and
     * then sees {@code used_at} already set.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PasswordResetToken t where t.tokenHash = :tokenHash")
    Optional<PasswordResetToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /** Invalidates every outstanding token before a new one is issued. */
    @Modifying
    @Query("update PasswordResetToken t set t.usedAt = :usedAt "
            + "where t.user.id = :userId and t.usedAt is null")
    int invalidateAllUnusedForUser(@Param("userId") Long userId,
                                   @Param("usedAt") OffsetDateTime usedAt);

    /** Invalidates the user's other outstanding tokens after a successful reset. */
    @Modifying
    @Query("update PasswordResetToken t set t.usedAt = :usedAt "
            + "where t.user.id = :userId and t.usedAt is null and t.id <> :tokenId")
    int invalidateOtherUnusedForUser(@Param("userId") Long userId,
                                     @Param("tokenId") Long tokenId,
                                     @Param("usedAt") OffsetDateTime usedAt);
}
