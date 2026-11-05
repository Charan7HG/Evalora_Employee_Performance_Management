package com.evalora.backend.entity;

import com.evalora.backend.entity.enums.AssignmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One appraisal: this employee, this cycle, this reviewing manager.
 * The status column drives the whole workflow, and the timestamp columns
 * record when each stage happened.
 *
 * manager is a snapshot. If the employee later gets a new manager, the
 * history of this cycle does not change.
 */
@Entity
@Table(name = "employee_assignments",
        uniqueConstraints = @UniqueConstraint(name = "uq_assignment_cycle_employee",
                columnNames = { "cycle_id", "employee_id" }))
@Getter
@Setter
@NoArgsConstructor
public class EmployeeAssignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cycle_id", nullable = false)
    private PerformanceCycle cycle;

    /** The appraised employee (a profile whose user has role EMPLOYEE). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    /** The manager responsible for reviewing this assignment. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "manager_id", nullable = false)
    private Employee manager;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 40)
    private AssignmentStatus status = AssignmentStatus.SELF_ASSESSMENT_PENDING;

    @Column(name = "self_submitted_at")
    private OffsetDateTime selfSubmittedAt;

    @Column(name = "manager_submitted_at")
    private OffsetDateTime managerSubmittedAt;

    @Column(name = "hr_finalized_at")
    private OffsetDateTime hrFinalizedAt;

    @Column(name = "results_published_at")
    private OffsetDateTime resultsPublishedAt;
}
