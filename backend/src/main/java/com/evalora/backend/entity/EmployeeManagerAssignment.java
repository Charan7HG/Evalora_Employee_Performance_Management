package com.evalora.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * One reporting-line period: this employee reported to this manager from
 * {@code effectiveFrom} until {@code effectiveTo}.
 *
 * <p>Rows are append-only: reassignment closes the current row and inserts a
 * new one. A null {@code effectiveTo} marks the employee's current manager.
 * The partial unique index {@code uq_ema_active_employee} in V2 allows only one
 * such row per employee.</p>
 *
 * <p>This is not the appraisal assignment - see {@link EmployeeAssignment}.</p>
 */
@Entity
@Table(name = "employee_manager_assignments")
@Getter
@Setter
@NoArgsConstructor
public class EmployeeManagerAssignment extends BaseEntity {

    /** The employee whose reporting line this row records. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    /** The manager responsible during this period. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "manager_id", nullable = false)
    private Employee manager;

    /** The HR user who made the assignment; null if that account no longer exists. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by")
    private User assignedBy;

    @Column(name = "effective_from", nullable = false)
    private OffsetDateTime effectiveFrom;

    /** Null means the assignment is still current. */
    @Column(name = "effective_to")
    private OffsetDateTime effectiveTo;
}
