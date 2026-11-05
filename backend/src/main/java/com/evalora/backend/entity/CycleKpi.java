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
 * A KPI selected for a cycle, with the weight used in that cycle.
 * weightage is NULL for every row -> the cycle uses the average mode.
 */
@Entity
@Table(name = "cycle_kpis",
        uniqueConstraints = @UniqueConstraint(name = "uq_cycle_kpi", columnNames = { "cycle_id", "kpi_id" }))
@Getter
@Setter
@NoArgsConstructor
public class CycleKpi extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cycle_id", nullable = false)
    private PerformanceCycle cycle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "kpi_id", nullable = false)
    private KpiMaster kpi;

    @Column(name = "weightage", precision = 5, scale = 2)
    private BigDecimal weightage;
}
