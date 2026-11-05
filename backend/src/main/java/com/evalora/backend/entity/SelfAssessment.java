package com.evalora.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The employee's own rating for one KPI. Reference only: it contributes 0%
 * to the final score and the scoring code never reads it.
 */
@Entity
@Table(name = "self_assessments",
        uniqueConstraints = @UniqueConstraint(name = "uq_self_assessment", columnNames = "employee_kpi_id"))
@Getter
@Setter
@NoArgsConstructor
public class SelfAssessment extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_kpi_id", nullable = false)
    private EmployeeKpi employeeKpi;

    /** 1-5. Nullable while the employee is still saving a draft. */
    @Column(name = "rating")
    private Short rating;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;
}
