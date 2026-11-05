package com.evalora.backend.repository;

import com.evalora.backend.entity.KpiMaster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KpiMasterRepository extends JpaRepository<KpiMaster, Long> {

    Optional<KpiMaster> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    List<KpiMaster> findByActiveTrue();

    /** Newest first: the HR catalogue lists the most recently created KPI first. */
    List<KpiMaster> findAllByOrderByIdDesc();

    List<KpiMaster> findByActiveOrderByIdDesc(boolean active);
}
