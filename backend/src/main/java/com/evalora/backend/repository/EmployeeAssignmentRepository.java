package com.evalora.backend.repository;

import com.evalora.backend.entity.EmployeeAssignment;
import com.evalora.backend.entity.enums.AssignmentStatus;
import com.evalora.backend.entity.enums.CycleStatus;
import com.evalora.backend.entity.enums.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmployeeAssignmentRepository extends JpaRepository<EmployeeAssignment, Long> {

    List<EmployeeAssignment> findByCycle_Id(Long cycleId);

    /** The roster of one cycle, employee and reviewing manager loaded. */
    @EntityGraph(attributePaths = { "employee", "manager" })
    List<EmployeeAssignment> findByCycle_IdOrderByEmployee_IdAsc(Long cycleId);

    Optional<EmployeeAssignment> findByCycle_IdAndEmployee_Id(Long cycleId, Long employeeId);

    List<EmployeeAssignment> findByEmployee_Id(Long employeeId);

    List<EmployeeAssignment> findByManager_Id(Long managerId);

    List<EmployeeAssignment> findByManager_IdAndStatus(Long managerId, AssignmentStatus status);

    long countByCycle_IdAndStatus(Long cycleId, AssignmentStatus status);

    @Query("""
            select count(a.id) from EmployeeAssignment a
            where a.employee.user.role = :employeeRole
              and a.cycle.status = :cycleStatus
              and a.status = :assignmentStatus
            """)
    long countByCycleStatusAndAssignmentStatusAndEmployeeRole(
            @Param("cycleStatus") CycleStatus cycleStatus,
            @Param("assignmentStatus") AssignmentStatus assignmentStatus,
            @Param("employeeRole") Role employeeRole);

    @Query("""
            select count(a.id) from EmployeeAssignment a
            where a.manager.user.id = :managerUserId
              and a.employee.user.role = :employeeRole
              and a.cycle.status = :cycleStatus
              and a.status = :assignmentStatus
            """)
    long countManagerAssignmentsByCycleStatusAndAssignmentStatus(
            @Param("managerUserId") Long managerUserId,
            @Param("cycleStatus") CycleStatus cycleStatus,
            @Param("assignmentStatus") AssignmentStatus assignmentStatus,
            @Param("employeeRole") Role employeeRole);

    @Query("""
            select count(a.id) from EmployeeAssignment a
            where a.employee.user.role = :employeeRole
              and a.status = :assignmentStatus
            """)
    long countByAssignmentStatusAndEmployeeRole(
            @Param("assignmentStatus") AssignmentStatus assignmentStatus,
            @Param("employeeRole") Role employeeRole);

    @Query("""
            select count(a.id) from EmployeeAssignment a
            where a.manager.user.id = :managerUserId
              and a.employee.user.role = :employeeRole
              and a.status = :assignmentStatus
            """)
    long countManagerAssignmentsByAssignmentStatus(
            @Param("managerUserId") Long managerUserId,
            @Param("assignmentStatus") AssignmentStatus assignmentStatus,
            @Param("employeeRole") Role employeeRole);

    /**
     * The employee's own assignments in exactly one cycle status - the
     * employee API only ever passes {@code PUBLISHED} - newest cycle first.
     * The cycle header is loaded with the row because the listing renders it
     * immediately.
     */
    @EntityGraph(attributePaths = { "cycle" })
    List<EmployeeAssignment> findByEmployee_IdAndCycle_StatusOrderByCycle_IdDesc(
            Long employeeId, CycleStatus status);

    /** Latest assignment visible to the employee in a currently published cycle. */
    @EntityGraph(attributePaths = { "cycle" })
    Optional<EmployeeAssignment> findFirstByEmployee_User_IdAndCycle_StatusOrderByCycle_IdDesc(
            Long userId, CycleStatus status);

    /**
     * Ownership-scoped read. A row that belongs to another employee (or does
     * not exist) is simply not returned, so the caller cannot tell the two
     * cases apart.
     */
    @EntityGraph(attributePaths = { "cycle" })
    Optional<EmployeeAssignment> findByIdAndEmployee_Id(Long id, Long employeeId);

    /**
     * Ownership-scoped write lock. Serialises draft saves and submissions for
     * one assignment, so two concurrent submits cannot both observe
     * {@code SELF_ASSESSMENT_PENDING}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from EmployeeAssignment a where a.id = :id and a.employee.id = :employeeId")
    Optional<EmployeeAssignment> findByIdAndEmployee_IdForUpdate(@Param("id") Long id,
                                                                 @Param("employeeId") Long employeeId);

    // ------------------------------------------------- manager assessment

    /**
     * The authenticated manager's pending-review queue. Ownership is the
     * {@code manager_id} snapshot stored when the cycle was configured, not the
     * employee's current manager, so a later reporting-line change does not
     * move an appraisal to someone else.
     */
    @EntityGraph(attributePaths = { "cycle", "employee" })
    @Query("""
            select a from EmployeeAssignment a
            where a.manager.id = :managerId
              and a.cycle.status = :cycleStatus
              and a.status = :assignmentStatus
                                                        and a.selfSubmittedAt is not null
            order by a.selfSubmittedAt desc, a.id desc
            """)
    List<EmployeeAssignment> findManagerReviewQueue(@Param("managerId") Long managerId,
                                                    @Param("cycleStatus") CycleStatus cycleStatus,
                                                    @Param("assignmentStatus") AssignmentStatus assignmentStatus);

    /**
     * Ownership-scoped manager read. A row reviewed by someone else (or one
     * that does not exist) is not returned, so both cases look identical.
     */
    @EntityGraph(attributePaths = { "cycle", "employee" })
    Optional<EmployeeAssignment> findByIdAndManager_Id(Long id, Long managerId);

    /** Ownership-scoped manager write lock, scoped by the assignment manager. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from EmployeeAssignment a where a.id = :id and a.manager.id = :managerId")
    Optional<EmployeeAssignment> findByIdAndManager_IdForUpdate(@Param("id") Long id,
                                                                @Param("managerId") Long managerId);

    // ------------------------------------------------------ HR assessment

    /**
     * The HR pending-review queue. HR reviews every eligible employee, so
     * there is no ownership predicate; the manager and employee are loaded
     * with the row because the listing renders both immediately. Newest
     * manager submission first, with the id as a deterministic tie-break.
     */
    @EntityGraph(attributePaths = { "cycle", "employee" })
    @Query("""
            select a from EmployeeAssignment a
            where a.cycle.status = :cycleStatus
              and a.status = :assignmentStatus
              and a.selfSubmittedAt is not null
              and a.managerSubmittedAt is not null
            order by a.managerSubmittedAt desc, a.id desc
            """)
    List<EmployeeAssignment> findHrReviewQueue(@Param("cycleStatus") CycleStatus cycleStatus,
                                               @Param("assignmentStatus") AssignmentStatus assignmentStatus);

    /**
     * HR write lock. HR has no ownership dimension, so the lock is on the
     * assignment id alone. Draft saves and submissions both take it before
     * checking status, which serialises them per assignment.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from EmployeeAssignment a where a.id = :id")
    Optional<EmployeeAssignment> findByIdForUpdate(@Param("id") Long id);

    /**
     * Locks every assignment in one cycle in stable ID order. Bulk result
     * publication takes the cycle lock first, then these assignment locks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from EmployeeAssignment a where a.cycle.id = :cycleId order by a.id asc")
    List<EmployeeAssignment> findByCycle_IdForUpdateOrderByIdAsc(@Param("cycleId") Long cycleId);

    // ------------------------------------------- dashboard status listings

    /**
     * HR dashboard destination list, scoped to one cycle status. Mirrors
     * {@link #countByCycleStatusAndAssignmentStatusAndEmployeeRole} exactly, so
     * a widget count and its list always agree.
     */
    @EntityGraph(attributePaths = { "cycle", "employee", "manager" })
    @Query("""
            select a from EmployeeAssignment a
            where a.cycle.status = :cycleStatus
              and a.status = :assignmentStatus
              and a.employee.user.role = :employeeRole
            order by a.cycle.id desc, a.employee.fullName asc, a.id asc
            """)
    List<EmployeeAssignment> findAllByCycleStatusAndAssignmentStatusAndEmployeeRole(
            @Param("cycleStatus") CycleStatus cycleStatus,
            @Param("assignmentStatus") AssignmentStatus assignmentStatus,
            @Param("employeeRole") Role employeeRole);

    /** HR dashboard destination list for completed assessments (all cycles). */
    @EntityGraph(attributePaths = { "cycle", "employee", "manager" })
    @Query("""
            select a from EmployeeAssignment a
            where a.status = :assignmentStatus
              and a.employee.user.role = :employeeRole
            order by a.cycle.id desc, a.employee.fullName asc, a.id asc
            """)
    List<EmployeeAssignment> findAllByAssignmentStatusAndEmployeeRole(
            @Param("assignmentStatus") AssignmentStatus assignmentStatus,
            @Param("employeeRole") Role employeeRole);

    /**
     * Manager dashboard destination list. The ownership predicate is the
     * assignment's {@code manager_id} snapshot and mirrors
     * {@link #countManagerAssignmentsByCycleStatusAndAssignmentStatus}.
     */
    @EntityGraph(attributePaths = { "cycle", "employee", "manager" })
    @Query("""
            select a from EmployeeAssignment a
            where a.manager.user.id = :managerUserId
              and a.cycle.status = :cycleStatus
              and a.status = :assignmentStatus
              and a.employee.user.role = :employeeRole
            order by a.cycle.id desc, a.employee.fullName asc, a.id asc
            """)
    List<EmployeeAssignment> findManagerAssignmentsByCycleStatusAndStatusAndEmployeeRole(
            @Param("managerUserId") Long managerUserId,
            @Param("cycleStatus") CycleStatus cycleStatus,
            @Param("assignmentStatus") AssignmentStatus assignmentStatus,
            @Param("employeeRole") Role employeeRole);

    /** Manager dashboard destination list for completed assessments (all cycles). */
    @EntityGraph(attributePaths = { "cycle", "employee", "manager" })
    @Query("""
            select a from EmployeeAssignment a
            where a.manager.user.id = :managerUserId
              and a.status = :assignmentStatus
              and a.employee.user.role = :employeeRole
            order by a.cycle.id desc, a.employee.fullName asc, a.id asc
            """)
    List<EmployeeAssignment> findManagerAssignmentsByStatusAndEmployeeRole(
            @Param("managerUserId") Long managerUserId,
            @Param("assignmentStatus") AssignmentStatus assignmentStatus,
            @Param("employeeRole") Role employeeRole);
}
