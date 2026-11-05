package com.evalora.backend.repository;

import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByRole(Role role);

    /**
     * Same lookup as {@link #findByEmailIgnoreCase(String)}, but with a write
     * lock on the row.
     *
     * <p>Login uses this so two simultaneous attempts for the same account
     * cannot read the same failed-attempt counter and overwrite each other.
     * Must be called inside a transaction (the lock is held until it ends).</p>
     *
     * @param email already normalised (trimmed, lowercase)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where lower(u.email) = :email")
    Optional<User> findByEmailForUpdate(@Param("email") String email);

    /**
     * Locks one account row by id. Password change and reset use this so two
     * concurrent password operations for the same account cannot interleave.
     * Must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
