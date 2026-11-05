package com.evalora.backend.entity;

import com.evalora.backend.entity.enums.StaffStatus;
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
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;

/**
 * Staff profile for HR, managers and employees. The role itself lives in
 * {@link User} - this table does not duplicate it.
 *
 * Only profiles whose user has role EMPLOYEE are appraised.
 * "status = INACTIVE" is the soft delete.
 */
@Entity
@Table(name = "employees",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_employees_user", columnNames = "user_id"),
                @UniqueConstraint(name = "uq_employees_code", columnNames = "employee_code")
        })
@Check(constraints = "manager_id IS NULL OR manager_id <> id")
@Getter
@Setter
@NoArgsConstructor
public class Employee extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "employee_code", nullable = false, length = 30)
    private String employeeCode;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "department", length = 100)
    private String department;

    @Column(name = "designation", length = 100)
    private String designation;

    /**
     * Reporting manager. Null only for HR and for managers (managers cannot
     * have a manager in v1). Unidirectional on purpose: the manager does not
     * keep a collection of subordinates, which keeps loading and JSON simple.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private Employee manager;

    @Column(name = "joining_date")
    private LocalDate joiningDate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private StaffStatus status = StaffStatus.ACTIVE;
}
