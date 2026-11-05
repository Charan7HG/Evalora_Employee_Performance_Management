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
 * The manager's rating for one KPI. Contributes 75% of the final score.
 * Exactly one row per employee KPI.
 */
@Entity
@Table(name = "manager_assessments",
        uniqueConstraints = @UniqueConstraint(name = "uq_manager_assessment", columnNames = "employee_kpi_id"))
@Getter
@Setter
@NoArgsConstructor
public class ManagerAssessment extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_kpi_id", nullable = false)
    private EmployeeKpi employeeKpi;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "manager_id", nullable = false)
    private Employee manager;

    /** 1-5. Nullable while the manager is still saving a draft. */
    @Column(name = "rating")
    private Short rating;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;
}
