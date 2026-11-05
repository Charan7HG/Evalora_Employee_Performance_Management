package com.evalora.backend.repository;

import com.evalora.backend.entity.PerformanceResult;
import com.evalora.backend.entity.enums.ResultStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface PerformanceResultRepository extends JpaRepository<PerformanceResult, Long> {

    Optional<PerformanceResult> findByAssignment_Id(Long assignmentId);

    @Query("select r.assignment.id from PerformanceResult r where r.id = :resultId")
    Optional<Long> findAssignmentIdById(@Param("resultId") Long resultId);

    List<PerformanceResult> findByAssignment_Cycle_Id(Long cycleId);

    @EntityGraph(attributePaths = {
            "assignment", "assignment.employee", "assignment.employee.user",
            "assignment.manager", "assignment.manager.user", "assignment.cycle"
    })
    @Query("""
            select r from PerformanceResult r
            where r.assignment.cycle.id = :cycleId
            order by r.assignment.id asc
            """)
    List<PerformanceResult> findAllForCycle(@Param("cycleId") Long cycleId);

    @EntityGraph(attributePaths = {
            "assignment", "assignment.employee", "assignment.employee.user",
            "assignment.manager", "assignment.manager.user", "assignment.cycle"
    })
    @Query("""
            select r from PerformanceResult r
            where r.status = :resultStatus
              and r.assignment.status = com.evalora.backend.entity.enums.AssignmentStatus.COMPLETED
              and r.assignment.hrFinalizedAt is not null
              and r.assignment.resultsPublishedAt is null
              and r.publishedAt is null
              and r.publishedBy is null
              and r.assignment.cycle.status = com.evalora.backend.entity.enums.CycleStatus.PUBLISHED
            order by r.assignment.cycle.id desc, r.assignment.id asc
            """)
    List<PerformanceResult> findAwaitingPublication(@Param("resultStatus") ResultStatus resultStatus);

    @EntityGraph(attributePaths = {
            "assignment", "assignment.employee", "assignment.employee.user",
            "assignment.manager", "assignment.manager.user", "assignment.cycle"
    })
    @Query("""
            select r from PerformanceResult r
            where r.id = :resultId
              and r.status = com.evalora.backend.entity.enums.ResultStatus.PUBLISHED
              and r.assignment.status = com.evalora.backend.entity.enums.AssignmentStatus.RESULTS_PUBLISHED
              and r.assignment.resultsPublishedAt is not null
              and r.publishedAt is not null
              and r.publishedAt = r.assignment.resultsPublishedAt
              and r.assignment.employee.user.id = :userId
            """)
    Optional<PerformanceResult> findPublishedByIdForEmployee(@Param("resultId") Long resultId,
                                                              @Param("userId") Long userId);

    @EntityGraph(attributePaths = {
            "assignment", "assignment.employee", "assignment.employee.user",
            "assignment.manager", "assignment.manager.user", "assignment.cycle"
    })
    @Query("""
            select r from PerformanceResult r
            where r.status = com.evalora.backend.entity.enums.ResultStatus.PUBLISHED
              and r.assignment.status = com.evalora.backend.entity.enums.AssignmentStatus.RESULTS_PUBLISHED
              and r.assignment.resultsPublishedAt is not null
              and r.publishedAt is not null
              and r.publishedAt = r.assignment.resultsPublishedAt
              and r.assignment.employee.user.id = :userId
            order by r.assignment.cycle.id desc, r.assignment.id desc
            """)
    List<PerformanceResult> findPublishedForEmployee(@Param("userId") Long userId);

    @EntityGraph(attributePaths = {
            "assignment", "assignment.employee", "assignment.employee.user",
            "assignment.manager", "assignment.manager.user", "assignment.cycle"
    })
    @Query("""
            select r from PerformanceResult r
            where r.id = :resultId
              and r.status = com.evalora.backend.entity.enums.ResultStatus.PUBLISHED
              and r.assignment.status = com.evalora.backend.entity.enums.AssignmentStatus.RESULTS_PUBLISHED
              and r.assignment.resultsPublishedAt is not null
              and r.publishedAt is not null
              and r.publishedAt = r.assignment.resultsPublishedAt
              and r.assignment.manager.user.id = :userId
            """)
    Optional<PerformanceResult> findPublishedByIdForManager(@Param("resultId") Long resultId,
                                                             @Param("userId") Long userId);

    @EntityGraph(attributePaths = {
            "assignment", "assignment.employee", "assignment.employee.user",
            "assignment.manager", "assignment.manager.user", "assignment.cycle"
    })
    @Query("""
            select r from PerformanceResult r
            where r.status = com.evalora.backend.entity.enums.ResultStatus.PUBLISHED
              and r.assignment.status = com.evalora.backend.entity.enums.AssignmentStatus.RESULTS_PUBLISHED
              and r.assignment.resultsPublishedAt is not null
              and r.publishedAt is not null
              and r.publishedAt = r.assignment.resultsPublishedAt
              and r.assignment.manager.user.id = :userId
            order by r.assignment.cycle.id desc, r.assignment.employee.fullName asc, r.assignment.id desc
            """)
    List<PerformanceResult> findPublishedForManager(@Param("userId") Long userId);

    @Query("""
            select count(r.id) from PerformanceResult r
            where r.status = com.evalora.backend.entity.enums.ResultStatus.PUBLISHED
              and r.assignment.status = com.evalora.backend.entity.enums.AssignmentStatus.RESULTS_PUBLISHED
              and r.publishedAt is not null
              and r.assignment.resultsPublishedAt is not null
              and r.publishedAt = r.assignment.resultsPublishedAt
              and r.assignment.employee.user.role = com.evalora.backend.entity.enums.Role.EMPLOYEE
            """)
    long countPublishedResultsVisibleToEmployees();

    @Query("""
            select count(r.id) from PerformanceResult r
            where r.status = com.evalora.backend.entity.enums.ResultStatus.PUBLISHED
              and r.assignment.status = com.evalora.backend.entity.enums.AssignmentStatus.RESULTS_PUBLISHED
              and r.publishedAt is not null
              and r.assignment.resultsPublishedAt is not null
              and r.publishedAt = r.assignment.resultsPublishedAt
              and r.assignment.manager.user.id = :userId
              and r.assignment.employee.user.role = com.evalora.backend.entity.enums.Role.EMPLOYEE
            """)
    long countPublishedResultsVisibleToManager(@Param("userId") Long userId);

    @EntityGraph(attributePaths = { "assignment", "assignment.cycle" })
    @Query("""
            select r from PerformanceResult r
            where r.status = com.evalora.backend.entity.enums.ResultStatus.PUBLISHED
              and r.assignment.status = com.evalora.backend.entity.enums.AssignmentStatus.RESULTS_PUBLISHED
              and r.publishedAt is not null
              and r.assignment.resultsPublishedAt is not null
              and r.publishedAt = r.assignment.resultsPublishedAt
              and r.assignment.employee.user.id = :userId
            order by r.publishedAt desc, r.id desc
            """)
    List<PerformanceResult> findLatestPublishedForEmployee(@Param("userId") Long userId, Pageable pageable);
}
