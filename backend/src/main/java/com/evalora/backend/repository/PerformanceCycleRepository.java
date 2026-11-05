package com.evalora.backend.repository;

import com.evalora.backend.entity.PerformanceCycle;
import com.evalora.backend.entity.enums.CycleStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PerformanceCycleRepository extends JpaRepository<PerformanceCycle, Long> {

    Optional<PerformanceCycle> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    List<PerformanceCycle> findByStatus(CycleStatus status);

    long countByStatus(CycleStatus status);

    long countByPublishedAtIsNotNull();

    /** Newest first: the HR cycle list shows the most recently created cycle first. */
    List<PerformanceCycle> findAllByOrderByIdDesc();

    /**
     * Locks one cycle for the rest of the transaction. Every mutation of a
     * cycle (update, KPI roster, employee roster, publish) takes this lock
     * first, so two HR requests cannot interleave on the same cycle.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PerformanceCycle c where c.id = :id")
    Optional<PerformanceCycle> findByIdForUpdate(@Param("id") Long id);
}
