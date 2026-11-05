package com.evalora.backend.entity;

import com.evalora.backend.entity.enums.CycleStatus;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One review period (for example "Annual 2026").
 *
 * The three deadlines are informational in v1: the UI shows them and marks
 * work as overdue, but they never block a submission. Workflow order is
 * enforced by the assignment status instead.
 */
@Entity
@Table(name = "performance_cycles",
        uniqueConstraints = @UniqueConstraint(name = "uq_cycles_name", columnNames = "name"))
@Getter
@Setter
@NoArgsConstructor
public class PerformanceCycle extends BaseEntity {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "self_assessment_deadline", nullable = false)
    private LocalDate selfAssessmentDeadline;

    @Column(name = "manager_review_deadline", nullable = false)
    private LocalDate managerReviewDeadline;

    @Column(name = "hr_review_deadline", nullable = false)
    private LocalDate hrReviewDeadline;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private CycleStatus status = CycleStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
