package com.evalora.backend.repository;

import com.evalora.backend.entity.HrAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HrAssessmentRepository extends JpaRepository<HrAssessment, Long> {

    Optional<HrAssessment> findByEmployeeKpi_Id(Long employeeKpiId);

    List<HrAssessment> findByEmployeeKpi_Assignment_Id(Long assignmentId);
}
