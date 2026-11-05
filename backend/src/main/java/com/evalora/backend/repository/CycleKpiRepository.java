package com.evalora.backend.repository;

import com.evalora.backend.entity.CycleKpi;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CycleKpiRepository extends JpaRepository<CycleKpi, Long> {

    List<CycleKpi> findByCycle_Id(Long cycleId);

    /** The configured KPIs of one cycle, with the master KPI loaded. */
    @EntityGraph(attributePaths = "kpi")
    List<CycleKpi> findByCycle_IdOrderByIdAsc(Long cycleId);

    Optional<CycleKpi> findByCycle_IdAndKpi_Id(Long cycleId, Long kpiId);

    boolean existsByKpi_Id(Long kpiId);
}
