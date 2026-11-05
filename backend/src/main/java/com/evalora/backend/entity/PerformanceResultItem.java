package com.evalora.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Permanent per-KPI breakdown of a result.
 *
 * Everything needed to show a historical report is copied into this row
 * (KPI name, weight, all three ratings, comments and the final KPI score).
 * Nothing here is recalculated, and nothing here changes when a master KPI,
 * a cycle or an employee profile is edited later.
 */
@Entity
@Table(name = "performance_result_items",
        uniqueConstraints = @UniqueConstraint(name = "uq_result_item",
                columnNames = { "result_id", "employee_kpi_id" }))
@Getter
@Setter
@NoArgsConstructor
public class PerformanceResultItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "result_id", nullable = false)
    private PerformanceResult result;

    /** Traceability link only - the copied values below are what we display. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_kpi_id", nullable = false)
    private EmployeeKpi employeeKpi;

    @Column(name = "kpi_name", nullable = false, length = 150)
    private String kpiName;

    @Column(name = "weightage", precision = 5, scale = 2)
    private BigDecimal weightage;

    /** Reference only, kept so the report can show it next to the other ratings. */
    @Column(name = "self_rating")
    private Short selfRating;

    @Column(name = "self_comment", columnDefinition = "text")
    private String selfComment;

    /** The manager rating, kept for every KPI including HR-assessed ones. */
    @Column(name = "manager_rating")
    private Short managerRating;

    @Column(name = "manager_comment", columnDefinition = "text")
    private String managerComment;

    /** Only an HR-assessed KPI has an HR rating; a manager KPI never does. */
    @Column(name = "hr_rating")
    private Short hrRating;

    @Column(name = "hr_comment", columnDefinition = "text")
    private String hrComment;

    /**
     * Manager KPI: the manager rating.
     * HR KPI: {@code (managerRating + hrRating) / 2}.
     */
    @Column(name = "final_score", nullable = false, precision = 4, scale = 2)
    private BigDecimal finalScore;
}
