package com.evalora.backend.service;

import com.evalora.backend.dto.ManagerAssessmentEntryRequest;
import com.evalora.backend.dto.ManagerAssessmentKpiResponse;
import com.evalora.backend.dto.ManagerAssessmentResponse;
import com.evalora.backend.dto.ManagerAssessmentStatusResponse;
import com.evalora.backend.dto.ManagerEmployeeSummary;
import com.evalora.backend.dto.ManagerQueueItemResponse;
import com.evalora.backend.dto.SaveManagerAssessmentDraftRequest;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.EmployeeAssignment;
import com.evalora.backend.entity.EmployeeKpi;
import com.evalora.backend.entity.ManagerAssessment;
import com.evalora.backend.entity.PerformanceCycle;
import com.evalora.backend.entity.SelfAssessment;
import com.evalora.backend.entity.enums.AssignmentStatus;
import com.evalora.backend.entity.enums.CycleStatus;
import com.evalora.backend.entity.enums.KpiType;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.exception.HrErrorType;
import com.evalora.backend.repository.EmployeeAssignmentRepository;
import com.evalora.backend.repository.EmployeeKpiRepository;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.ManagerAssessmentRepository;
import com.evalora.backend.repository.SelfAssessmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manager-side review of an employee's submitted self-assessment: the pending
 * queue, the review screen, draft ratings and the one-way submission into HR
 * review.
 *
 * <p>Authorization uses the manager recorded on the assignment when the cycle
 * was configured ({@code employee_assignments.manager_id}), never the
 * employee's current reporting line. Every lookup is scoped by that id, so
 * another manager's assignment and a missing one are indistinguishable (404).
 * The cycle must be exactly {@code PUBLISHED} and the employee must already
 * have submitted; an assignment still waiting for the employee is hidden the
 * same way.</p>
 *
 * <p>Draft saves and submissions take a pessimistic write lock on the
 * assignment row before reading its status, so they serialise per assignment:
 * two submissions cannot both observe {@code MANAGER_REVIEW_PENDING}, and a
 * draft can never be written after a submission commits. Draft saves upsert
 * per assigned KPI; the unique key on {@code manager_assessments.employee_kpi_id}
 * is the database backstop. Submission moves the assignment to
 * {@link AssignmentStatus#HR_REVIEW_PENDING} and records
 * {@code manager_submitted_at} in the same transaction. Nothing here computes
 * a score or touches the employee's self-assessment.</p>
 */
@Service
public class ManagerAssessmentService {

    private static final short MIN_RATING = 1;
    private static final short MAX_RATING = 5;

    /**
     * Statuses at (or after) manager review. The manager can always read their
     * own review screen, including after HR review starts; only
     * {@code SELF_ASSESSMENT_PENDING} is not theirs to see yet.
     */
    private static final Set<AssignmentStatus> VISIBLE_STATUSES = EnumSet.of(
            AssignmentStatus.MANAGER_REVIEW_PENDING,
            AssignmentStatus.HR_REVIEW_PENDING,
            AssignmentStatus.COMPLETED,
            AssignmentStatus.RESULTS_PUBLISHED);

    private final EmployeeRepository employeeRepository;
    private final EmployeeAssignmentRepository assignmentRepository;
    private final EmployeeKpiRepository employeeKpiRepository;
    private final SelfAssessmentRepository selfAssessmentRepository;
    private final ManagerAssessmentRepository managerAssessmentRepository;

    public ManagerAssessmentService(EmployeeRepository employeeRepository,
                                    EmployeeAssignmentRepository assignmentRepository,
                                    EmployeeKpiRepository employeeKpiRepository,
                                    SelfAssessmentRepository selfAssessmentRepository,
                                    ManagerAssessmentRepository managerAssessmentRepository) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.employeeKpiRepository = employeeKpiRepository;
        this.selfAssessmentRepository = selfAssessmentRepository;
        this.managerAssessmentRepository = managerAssessmentRepository;
    }

    // ---------------------------------------------------------------- queue

    @Transactional(readOnly = true)
    public List<ManagerQueueItemResponse> listPending(Long userId) {
        Employee manager = requireManager(userId);
        return assignmentRepository
                .findManagerReviewQueue(manager.getId(), CycleStatus.PUBLISHED,
                        AssignmentStatus.MANAGER_REVIEW_PENDING)
                .stream()
                .map(this::toQueueItem)
                .toList();
    }

    // --------------------------------------------------------------- detail

    @Transactional(readOnly = true)
    public ManagerAssessmentResponse get(Long userId, Long assignmentId) {
        Employee manager = requireManager(userId);
        EmployeeAssignment assignment = requireVisibleAssignment(manager.getId(), assignmentId);
        return toResponse(assignment);
    }

    @Transactional(readOnly = true)
    public ManagerAssessmentStatusResponse getStatus(Long userId, Long assignmentId) {
        Employee manager = requireManager(userId);
        EmployeeAssignment assignment = requireVisibleAssignment(manager.getId(), assignmentId);
        List<EmployeeKpi> kpis = orderedKpis(assignmentId);
        return toStatusResponse(assignment, kpis, savedByEmployeeKpi(assignmentId));
    }

    // ---------------------------------------------------------------- draft

    /**
     * Creates or updates the manager's saved draft. The complete request is
     * validated before any row is written, so a bad entry never leaves a
     * partial save behind. Status and timestamps are never touched.
     */
    @Transactional
    public ManagerAssessmentResponse saveDraft(Long userId,
                                               Long assignmentId,
                                               SaveManagerAssessmentDraftRequest request) {
        Employee manager = requireManager(userId);
        EmployeeAssignment assignment = lockVisibleAssignment(manager.getId(), assignmentId);
        requireEditable(assignment);

        List<EmployeeKpi> kpis = orderedKpis(assignmentId);
        Map<Long, EmployeeKpi> kpisById = new HashMap<>();
        for (EmployeeKpi kpi : kpis) {
            kpisById.put(kpi.getId(), kpi);
        }

        List<ManagerAssessmentEntryRequest> entries =
                request == null || request.kpis() == null ? List.of() : request.kpis();

        Set<Long> seen = new HashSet<>();
        List<ValidatedEntry> validated = new ArrayList<>(entries.size());
        for (ManagerAssessmentEntryRequest entry : entries) {
            if (entry == null || entry.employeeKpiId() == null) {
                throw invalid("Employee KPI id is required.");
            }
            EmployeeKpi kpi = kpisById.get(entry.employeeKpiId());
            if (kpi == null) {
                throw invalid("The selected KPI does not belong to this assessment.");
            }
            if (!seen.add(kpi.getId())) {
                throw invalid("The same KPI cannot be rated twice in one request.");
            }
            validated.add(new ValidatedEntry(kpi,
                    toShortRating(entry.rating()),
                    normalizeOptional(entry.comment())));
        }

        Map<Long, ManagerAssessment> saved = savedByEmployeeKpi(assignmentId);
        List<ManagerAssessment> changed = new ArrayList<>(validated.size());
        for (ValidatedEntry entry : validated) {
            ManagerAssessment row = saved.get(entry.kpi().getId());
            if (row == null) {
                // Do not materialise a row that carries no information yet.
                if (entry.rating() == null && entry.comment() == null) {
                    continue;
                }
                row = new ManagerAssessment();
                row.setEmployeeKpi(entry.kpi());
                row.setManager(manager);
            }
            row.setRating(entry.rating());
            row.setComment(entry.comment());
            changed.add(row);
        }
        if (!changed.isEmpty()) {
            managerAssessmentRepository.saveAll(changed);
            managerAssessmentRepository.flush();
        }

        return toResponse(assignment);
    }

    // --------------------------------------------------------------- submit

    /**
     * Submits the saved draft. Every assigned KPI - manager-assessed and
     * HR-assessed alike - must already have a manager rating from 1 to 5. The
     * assignment moves to HR review and the manager submission timestamp is
     * recorded in the same transaction; the cycle and the employee's
     * self-assessment are not touched.
     */
    @Transactional
    public ManagerAssessmentStatusResponse submit(Long userId, Long assignmentId) {
        Employee manager = requireManager(userId);
        EmployeeAssignment assignment = lockVisibleAssignment(manager.getId(), assignmentId);
        requireEditable(assignment);

        List<EmployeeKpi> kpis = orderedKpis(assignmentId);
        if (kpis.isEmpty()) {
            throw conflict("No KPIs are assigned to this assessment.");
        }
        Map<Long, ManagerAssessment> saved = savedByEmployeeKpi(assignmentId);
        for (EmployeeKpi kpi : kpis) {
            ManagerAssessment row = saved.get(kpi.getId());
            if (row == null || row.getRating() == null || !isValidRating(row.getRating())) {
                throw conflict("Every assigned KPI must have a manager rating from 1 to 5 before submitting.");
            }
        }

        assignment.setManagerSubmittedAt(now());
        assignment.setStatus(AssignmentStatus.HR_REVIEW_PENDING);
        assignmentRepository.saveAndFlush(assignment);

        return toStatusResponse(assignment, kpis, saved);
    }

    // -------------------------------------------------------------- helpers

    /**
     * The authenticated user's own manager profile. The role check is
     * redundant with the security chain but keeps direct service calls honest.
     */
    private Employee requireManager(Long userId) {
        Employee manager = employeeRepository.findByUser_Id(userId)
                .orElseThrow(() -> notFound("Manager profile not found."));
        if (manager.getUser().getRole() != Role.MANAGER) {
            throw notFound("Manager profile not found.");
        }
        return manager;
    }

    private EmployeeAssignment requireVisibleAssignment(Long managerId, Long assignmentId) {
        EmployeeAssignment assignment = assignmentRepository
                .findByIdAndManager_Id(assignmentId, managerId)
                .orElseThrow(() -> notFound("Manager assessment not found."));
        requireVisible(assignment);
        return assignment;
    }

    private EmployeeAssignment lockVisibleAssignment(Long managerId, Long assignmentId) {
        EmployeeAssignment assignment = assignmentRepository
                .findByIdAndManager_IdForUpdate(assignmentId, managerId)
                .orElseThrow(() -> notFound("Manager assessment not found."));
        requireVisible(assignment);
        return assignment;
    }

    /**
     * Only a published cycle whose employee has already submitted is a review
     * the manager may see. A DRAFT (or future) cycle, an assignment still
     * waiting for the employee, another manager's assignment and an unknown id
     * all produce the same 404.
     */
    private static void requireVisible(EmployeeAssignment assignment) {
        if (assignment.getCycle() == null
                || assignment.getCycle().getStatus() != CycleStatus.PUBLISHED
                || assignment.getSelfSubmittedAt() == null
                || !VISIBLE_STATUSES.contains(assignment.getStatus())) {
            throw notFound("Manager assessment not found.");
        }
    }

    private static void requireEditable(EmployeeAssignment assignment) {
        if (assignment.getStatus() != AssignmentStatus.MANAGER_REVIEW_PENDING) {
            throw conflict("The manager assessment has already been submitted.");
        }
    }

    private List<EmployeeKpi> orderedKpis(Long assignmentId) {
        return employeeKpiRepository.findByAssignment_IdOrderByIdAsc(assignmentId);
    }

    private Map<Long, ManagerAssessment> savedByEmployeeKpi(Long assignmentId) {
        Map<Long, ManagerAssessment> saved = new HashMap<>();
        for (ManagerAssessment assessment
                : managerAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId)) {
            saved.put(assessment.getEmployeeKpi().getId(), assessment);
        }
        return saved;
    }

    private Map<Long, SelfAssessment> selfByEmployeeKpi(Long assignmentId) {
        Map<Long, SelfAssessment> self = new HashMap<>();
        for (SelfAssessment assessment : selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId)) {
            self.put(assessment.getEmployeeKpi().getId(), assessment);
        }
        return self;
    }

    /** Rejects decimals instead of letting JSON truncate them silently. */
    private static Short toShortRating(BigDecimal rating) {
        if (rating == null) {
            return null;
        }
        if (rating.stripTrailingZeros().scale() > 0) {
            throw invalid("Manager assessment rating must be a whole number between 1 and 5.");
        }
        short value;
        try {
            value = rating.shortValueExact();
        } catch (ArithmeticException ex) {
            throw invalid("Manager assessment rating must be a whole number between 1 and 5.");
        }
        if (!isValidRating(value)) {
            throw invalid("Manager assessment rating must be a whole number between 1 and 5.");
        }
        return value;
    }

    private static boolean isValidRating(short rating) {
        return rating >= MIN_RATING && rating <= MAX_RATING;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ------------------------------------------------------------ responses

    /** Legacy rows without an explicit type are manager KPIs. */
    private static KpiType kpiTypeOf(EmployeeKpi kpi) {
        return kpi.getKpiType() == null ? KpiType.MANAGER : kpi.getKpiType();
    }

    private ManagerQueueItemResponse toQueueItem(EmployeeAssignment assignment) {
        Employee employee = assignment.getEmployee();
        PerformanceCycle cycle = assignment.getCycle();
        return new ManagerQueueItemResponse(
                assignment.getId(),
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getFullName(),
                cycle.getId(),
                cycle.getName(),
                assignment.getSelfSubmittedAt(),
                assignment.getStatus().name());
    }

    private ManagerAssessmentResponse toResponse(EmployeeAssignment assignment) {
        PerformanceCycle cycle = assignment.getCycle();
        Employee employee = assignment.getEmployee();
        List<EmployeeKpi> kpis = orderedKpis(assignment.getId());
        Map<Long, SelfAssessment> self = selfByEmployeeKpi(assignment.getId());
        Map<Long, ManagerAssessment> manager = savedByEmployeeKpi(assignment.getId());

        List<ManagerAssessmentKpiResponse> items = new ArrayList<>(kpis.size());
        for (EmployeeKpi kpi : kpis) {
            SelfAssessment selfRow = self.get(kpi.getId());
            ManagerAssessment managerRow = manager.get(kpi.getId());
            items.add(new ManagerAssessmentKpiResponse(
                    kpi.getId(),
                    kpi.getKpi() == null ? null : kpi.getKpi().getId(),
                    kpi.getKpiName(),
                    kpi.getKpiDescription(),
                    kpi.getKpiCategory(),
                    kpiTypeOf(kpi).name(),
                    kpi.getWeightage(),
                    selfRow == null || selfRow.getRating() == null ? null : selfRow.getRating().intValue(),
                    selfRow == null ? null : selfRow.getComment(),
                    managerRow == null || managerRow.getRating() == null
                            ? null : managerRow.getRating().intValue(),
                    managerRow == null ? null : managerRow.getComment()));
        }

        return new ManagerAssessmentResponse(
                assignment.getId(),
                cycle.getId(),
                cycle.getName(),
                cycle.getStatus().name(),
                cycle.getStartDate(),
                cycle.getEndDate(),
                cycle.getManagerReviewDeadline(),
                assignment.getStatus().name(),
                assignment.getSelfSubmittedAt(),
                assignment.getManagerSubmittedAt(),
                assignment.getStatus() == AssignmentStatus.MANAGER_REVIEW_PENDING,
                new ManagerEmployeeSummary(
                        employee.getId(),
                        employee.getEmployeeCode(),
                        employee.getFullName(),
                        employee.getDepartment(),
                        employee.getDesignation()),
                items);
    }

    private ManagerAssessmentStatusResponse toStatusResponse(EmployeeAssignment assignment,
                                                             List<EmployeeKpi> kpis,
                                                             Map<Long, ManagerAssessment> saved) {
        int rated = 0;
        for (EmployeeKpi kpi : kpis) {
            ManagerAssessment row = saved.get(kpi.getId());
            if (row != null && row.getRating() != null) {
                rated++;
            }
        }
        boolean submitted = assignment.getStatus() != AssignmentStatus.MANAGER_REVIEW_PENDING;
        return new ManagerAssessmentStatusResponse(
                assignment.getId(),
                assignment.getCycle().getId(),
                assignment.getStatus().name(),
                kpis.size(),
                rated,
                submitted,
                assignment.getManagerSubmittedAt());
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static HrApiException notFound(String message) {
        return new HrApiException(HrErrorType.NOT_FOUND, message);
    }

    private static HrApiException conflict(String message) {
        return new HrApiException(HrErrorType.CONFLICT, message);
    }

    private static HrApiException invalid(String message) {
        return new HrApiException(HrErrorType.INVALID_REQUEST, message);
    }

    private record ValidatedEntry(EmployeeKpi kpi, Short rating, String comment) {
    }
}
