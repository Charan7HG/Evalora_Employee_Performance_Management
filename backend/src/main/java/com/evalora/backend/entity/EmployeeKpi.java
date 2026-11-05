package com.evalora.backend.entity;

import com.evalora.backend.entity.enums.KpiType;
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

import java.math.BigDecimal;

/**
 * Frozen KPI for one assignment. Created once, when the cycle is published.
 *
 * kpiName / kpiDescription / kpiCategory are copies of the master values at
 * that moment, so renaming or editing the KPI later never rewrites history.
 * The kpi_id reference is kept only for traceability.
 */
@Entity
@Table(name = "employee_kpis",
        uniqueConstraints = @UniqueConstraint(name = "uq_employee_kpi",
                columnNames = { "assignment_id", "kpi_id" }))
@Getter
@Setter
@NoArgsConstructor
public class EmployeeKpi extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false)
    private EmployeeAssignment assignment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "kpi_id", nullable = false)
    private KpiMaster kpi;

    @Column(name = "kpi_name", nullable = false, length = 150)
    private String kpiName;

    @Column(name = "kpi_description", columnDefinition = "text")
    private String kpiDescription;

    @Column(name = "kpi_category", length = 80)
    private String kpiCategory;

    /** Frozen copy of the master KPI classification at publication time. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kpi_type", nullable = false, length = 20)
    private KpiType kpiType = KpiType.MANAGER;

    @Column(name = "weightage", precision = 5, scale = 2)
    private BigDecimal weightage;
}
