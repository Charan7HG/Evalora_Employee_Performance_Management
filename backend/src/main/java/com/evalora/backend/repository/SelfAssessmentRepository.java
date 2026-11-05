package com.evalora.backend.repository;

import com.evalora.backend.entity.SelfAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SelfAssessmentRepository extends JpaRepository<SelfAssessment, Long> {

    Optional<SelfAssessment> findByEmployeeKpi_Id(Long employeeKpiId);

    List<SelfAssessment> findByEmployeeKpi_Assignment_Id(Long assignmentId);
}
