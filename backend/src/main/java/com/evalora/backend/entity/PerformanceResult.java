package com.evalora.backend.entity;

import com.evalora.backend.entity.enums.CalculationMode;
import com.evalora.backend.entity.enums.PerformanceGrade;
import com.evalora.backend.entity.enums.ResultStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The single official result of one assignment.
 *
 * Written once by HR finalisation and never recalculated afterwards.
 * Publishing only flips DRAFT -> PUBLISHED and records who/when.
 */
@Entity
@Table(name = "performance_results",
        uniqueConstraints = @UniqueConstraint(name = "uq_result_assignment", columnNames = "assignment_id"))
@Getter
@Setter
@NoArgsConstructor
public class PerformanceResult extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false)
    private EmployeeAssignment assignment;

    @Column(name = "overall_score", nullable = false, precision = 4, scale = 2)
    private BigDecimal overallScore;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "grade", nullable = false, length = 30)
    private PerformanceGrade grade;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "calculation_mode", nullable = false, length = 10)
    private CalculationMode calculationMode;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private ResultStatus status = ResultStatus.DRAFT;

    @Column(name = "computed_at", nullable = false)
    private OffsetDateTime computedAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "published_by")
    private User publishedBy;
}
