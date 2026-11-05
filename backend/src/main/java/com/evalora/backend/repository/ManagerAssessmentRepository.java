package com.evalora.backend.repository;

import com.evalora.backend.entity.ManagerAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ManagerAssessmentRepository extends JpaRepository<ManagerAssessment, Long> {

    Optional<ManagerAssessment> findByEmployeeKpi_Id(Long employeeKpiId);

    List<ManagerAssessment> findByEmployeeKpi_Assignment_Id(Long assignmentId);
}
