package com.evalora.backend.repository;

import com.evalora.backend.entity.EmployeeKpi;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmployeeKpiRepository extends JpaRepository<EmployeeKpi, Long> {

    List<EmployeeKpi> findByAssignment_Id(Long assignmentId);

    /** Stable display order for one assignment's frozen KPIs. */
    List<EmployeeKpi> findByAssignment_IdOrderByIdAsc(Long assignmentId);

    long countByAssignment_Id(Long assignmentId);
}
