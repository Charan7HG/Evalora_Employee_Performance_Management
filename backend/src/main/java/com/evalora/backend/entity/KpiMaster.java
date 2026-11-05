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
 * Reusable KPI definition. KPIs are never hard deleted: set active = false.
 * Values here are only the defaults; the copies actually used by a cycle are
 * snapshotted into {@link CycleKpi} and {@link EmployeeKpi}.
 */
@Entity
@Table(name = "kpi_master",
        uniqueConstraints = @UniqueConstraint(name = "uq_kpi_master_name", columnNames = "name"))
@Getter
@Setter
@NoArgsConstructor
public class KpiMaster extends BaseEntity {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "category", length = 80)
    private String category;

    /** Who rates this KPI: the manager (75%) or HR only. Defaults to MANAGER. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kpi_type", nullable = false, length = 20)
    private KpiType kpiType = KpiType.MANAGER;

    /** Optional suggestion only. The effective weight is stored per cycle. */
    @Column(name = "default_weightage", precision = 5, scale = 2)
    private BigDecimal defaultWeightage;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;
}
