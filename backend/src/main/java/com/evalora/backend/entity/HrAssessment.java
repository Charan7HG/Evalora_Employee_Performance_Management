package com.evalora.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The HR rating for one KPI. Contributes 25% of the final score.
 * Exactly one row per employee KPI.
 */
@Entity
@Table(name = "hr_assessments",
        uniqueConstraints = @UniqueConstraint(name = "uq_hr_assessment", columnNames = "employee_kpi_id"))
@Getter
@Setter
@NoArgsConstructor
public class HrAssessment extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_kpi_id", nullable = false)
    private EmployeeKpi employeeKpi;

    /** The HR user who wrote the rating. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hr_id", nullable = false)
    private User hr;

    /** 1-5. Nullable while HR is still saving a draft. */
    @Column(name = "rating")
    private Short rating;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;
}
