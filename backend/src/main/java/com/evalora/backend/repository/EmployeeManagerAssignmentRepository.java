package com.evalora.backend.repository;

import com.evalora.backend.entity.EmployeeManagerAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmployeeManagerAssignmentRepository
        extends JpaRepository<EmployeeManagerAssignment, Long> {

    /** Open reporting-line periods for one employee; normally zero or one. */
    List<EmployeeManagerAssignment> findByEmployee_IdAndEffectiveToIsNull(Long employeeId);

    /** Every reporting-line period for one employee, oldest first. */
    List<EmployeeManagerAssignment> findByEmployee_IdOrderByEffectiveFromAsc(Long employeeId);

    /** Current assignments where this employee is the manager. */
    List<EmployeeManagerAssignment> findByManager_IdAndEffectiveToIsNull(Long managerId);

    long countByEmployee_IdAndEffectiveToIsNull(Long employeeId);
}
