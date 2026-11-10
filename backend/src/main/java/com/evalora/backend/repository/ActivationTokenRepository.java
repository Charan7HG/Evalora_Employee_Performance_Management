package com.evalora.backend.repository;

import com.evalora.backend.entity.ActivationTokenEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ActivationTokenRepository extends JpaRepository<ActivationTokenEntity, Long> {

    Optional<ActivationTokenEntity> findByTokenHash(String tokenHash);

    List<ActivationTokenEntity> findByUser_Id(Long userId);

    void deleteAllByUser_Id(Long userId);

    /**
     * Reads only the owner id, without loading the token entity.
     *
     * <p>Activation needs the user row before the token row so that all
     * activation and forgot-password transactions acquire locks in the same order.
     * A scalar projection avoids putting a stale token in the persistence
     * context before the write lock is taken.</p>
     */
    @Query("select t.user.id from ActivationTokenEntity t where t.tokenHash = :tokenHash")
    Optional<Long> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * Locks the token row for the rest of the transaction. A successful
     * activation deletes the token before commit, so a concurrent request that
     * waits for this lock will no longer find it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ActivationTokenEntity t where t.tokenHash = :tokenHash")
    Optional<ActivationTokenEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

}