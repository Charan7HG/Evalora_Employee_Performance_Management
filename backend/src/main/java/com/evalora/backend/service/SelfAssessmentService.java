package com.evalora.backend.service;

import com.evalora.backend.dto.EmployeeCycleResponse;
import com.evalora.backend.dto.SaveSelfAssessmentDraftRequest;
import com.evalora.backend.dto.SelfAssessmentEntryRequest;
import com.evalora.backend.dto.SelfAssessmentKpiResponse;
import com.evalora.backend.dto.SelfAssessmentResponse;
import com.evalora.backend.dto.SelfAssessmentStatusResponse;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.EmployeeAssignment;
import com.evalora.backend.entity.EmployeeKpi;
import com.evalora.backend.entity.PerformanceCycle;
import com.evalora.backend.entity.SelfAssessment;
import com.evalora.backend.entity.enums.AssignmentStatus;
import com.evalora.backend.entity.enums.CycleStatus;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.exception.HrErrorType;
import com.evalora.backend.repository.EmployeeAssignmentRepository;
import com.evalora.backend.repository.EmployeeKpiRepository;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.SelfAssessmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Employee-side self-assessment: the employee's own view of published
 * assignments, draft ratings and the one-way submission into manager review.
 *
 * <p>Ownership is always derived from the authenticated user id and the
 * database relationship - never from a request field. Every lookup is scoped
 * by {@code employee_id}, so an assignment that belongs to someone else is
 * indistinguishable from a missing one (404). Only assignments whose cycle is
 * exactly {@code PUBLISHED} are visible; every other cycle status, including
 * {@code DRAFT}, is hidden with the same 404. That also covers the draft
 * roster rows Phase 3D stores in {@code employee_assignments}.</p>
 *
 * <p>Draft saves and submissions take a pessimistic write lock on the
 * assignment row before reading its status, so the two operations serialise
 * per assignment: a draft can never be written after a submission commits,
 * and two submissions cannot both observe
 * {@code SELF_ASSESSMENT_PENDING}.</p>
 *
 * <p>Draft saves upsert per assigned KPI, so repeated saves never create a
 * second row per KPI (the unique key on {@code employee_kpi_id} is the last
 * line of defence). Submission validates the saved draft and moves the
 * assignment from {@link AssignmentStatus#SELF_ASSESSMENT_PENDING} to
 * {@link AssignmentStatus#MANAGER_REVIEW_PENDING} in one transaction. The
 * self-rating is reference only: nothing here computes a score.</p>
 */
@Service
public class SelfAssessmentService {

    private static final short MIN_RATING = 1;
    private static final short MAX_RATING = 5;

    private final EmployeeRepository employeeRepository;
    private final EmployeeAssignmentRepository assignmentRepository;
    private final EmployeeKpiRepository employeeKpiRepository;
    private final SelfAssessmentRepository selfAssessmentRepository;

    public SelfAssessmentService(EmployeeRepository employeeRepository,
                                 EmployeeAssignmentRepository assignmentRepository,
                                 EmployeeKpiRepository employeeKpiRepository,
                                 SelfAssessmentRepository selfAssessmentRepository) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.employeeKpiRepository = employeeKpiRepository;
        this.selfAssessmentRepository = selfAssessmentRepository;
    }

    // --------------------------------------------------------------- listing

    /**
     * The authenticated employee's assignments in cycles that are exactly
     * {@code PUBLISHED}. Every other status - including a future one - stays
     * invisible until a later phase explicitly widens this.
     */
    @Transactional(readOnly = true)
    public List<EmployeeCycleResponse> listCycles(Long userId) {
        Employee employee = requireEmployee(userId);
        return assignmentRepository
                .findByEmployee_IdAndCycle_StatusOrderByCycle_IdDesc(employee.getId(), CycleStatus.PUBLISHED)
                .stream()
                .map(this::toCycleResponse)
                .toList();
    }

    // ----------------------------------------------------------------- detail

    @Transactional(readOnly = true)
    public SelfAssessmentResponse get(Long userId, Long assignmentId) {
        Employee employee = requireEmployee(userId);
        EmployeeAssignment assignment = requireVisibleAssignment(employee.getId(), assignmentId);
        return toResponse(assignment);
    }

    @Transactional(readOnly = true)
    public SelfAssessmentStatusResponse getStatus(Long userId, Long assignmentId) {
        Employee employee = requireEmployee(userId);
        EmployeeAssignment assignment = requireVisibleAssignment(employee.getId(), assignmentId);
        List<EmployeeKpi> kpis = orderedKpis(assignmentId);
        Map<Long, SelfAssessment> saved = savedByEmployeeKpi(assignmentId);
        return toStatusResponse(assignment, kpis, saved);
    }

    // ----------------------------------------------------------------- draft

    /**
     * Creates or updates the employee's saved draft. The complete request is
     * validated before any row is written, so a bad entry never leaves a
     * partial save behind.
     */
    @Transactional
    public SelfAssessmentResponse saveDraft(Long userId,
                                            Long assignmentId,
                                            SaveSelfAssessmentDraftRequest request) {
        Employee employee = requireEmployee(userId);
        EmployeeAssignment assignment = lockVisibleAssignment(employee.getId(), assignmentId);
        requirePendingSelfAssessment(assignment);

        List<EmployeeKpi> kpis = orderedKpis(assignmentId);
        Map<Long, EmployeeKpi> kpisById = new HashMap<>();
        for (EmployeeKpi kpi : kpis) {
            kpisById.put(kpi.getId(), kpi);
        }

        List<SelfAssessmentEntryRequest> entries =
                request == null || request.kpis() == null ? List.of() : request.kpis();

        Set<Long> seen = new HashSet<>();
        List<ValidatedEntry> validated = new ArrayList<>(entries.size());
        for (SelfAssessmentEntryRequest entry : entries) {
            if (entry == null || entry.employeeKpiId() == null) {
                throw invalid("Employee KPI id is required.");
            }
            EmployeeKpi kpi = kpisById.get(entry.employeeKpiId());
            if (kpi == null) {
                throw invalid("The selected KPI does not belong to this self-assessment.");
            }
            if (!seen.add(kpi.getId())) {
                throw invalid("The same KPI cannot be rated twice in one request.");
            }
            validated.add(new ValidatedEntry(kpi,
                    toShortRating(entry.rating()),
                    normalizeOptional(entry.comment())));
        }

        Map<Long, SelfAssessment> saved = savedByEmployeeKpi(assignmentId);
        List<SelfAssessment> changed = new ArrayList<>(validated.size());
        for (ValidatedEntry entry : validated) {
            SelfAssessment row = saved.get(entry.kpi().getId());
            if (row == null) {
                // Do not materialise a row that carries no information yet.
                if (entry.rating() == null && entry.comment() == null) {
                    continue;
                }
                row = new SelfAssessment();
                row.setEmployeeKpi(entry.kpi());
            }
            row.setRating(entry.rating());
            row.setComment(entry.comment());
            changed.add(row);
        }
        if (!changed.isEmpty()) {
            selfAssessmentRepository.saveAll(changed);
            selfAssessmentRepository.flush();
        }

        return toResponse(assignment);
    }

    // ----------------------------------------------------------------- submit

    /**
     * Submits the saved draft. Every assigned KPI must already have a rating
     * from 1 to 5. The assignment moves to manager review and the submission
     * timestamp is recorded; the cycle itself is not touched.
     */
    @Transactional
    public SelfAssessmentStatusResponse submit(Long userId, Long assignmentId) {
        Employee employee = requireEmployee(userId);
        EmployeeAssignment assignment = lockVisibleAssignment(employee.getId(), assignmentId);
        requirePendingSelfAssessment(assignment);

        List<EmployeeKpi> kpis = orderedKpis(assignmentId);
        if (kpis.isEmpty()) {
            throw conflict("No KPIs are assigned to this self-assessment.");
        }
        Map<Long, SelfAssessment> saved = savedByEmployeeKpi(assignmentId);
        for (EmployeeKpi kpi : kpis) {
            SelfAssessment row = saved.get(kpi.getId());
            if (row == null || row.getRating() == null || !isValidRating(row.getRating())) {
                throw conflict("Every KPI must be rated from 1 to 5 before submitting.");
            }
        }

        assignment.setSelfSubmittedAt(now());
        assignment.setStatus(AssignmentStatus.MANAGER_REVIEW_PENDING);
        assignmentRepository.saveAndFlush(assignment);

        return toStatusResponse(assignment, kpis, saved);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The authenticated user's own profile. The role check is redundant with
     * the security chain but keeps direct service calls honest.
     */
    private Employee requireEmployee(Long userId) {
        Employee employee = employeeRepository.findByUser_Id(userId)
                .orElseThrow(() -> notFound("Employee profile not found."));
        if (employee.getUser().getRole() != Role.EMPLOYEE) {
            throw notFound("Employee profile not found.");
        }
        return employee;
    }

    private EmployeeAssignment requireVisibleAssignment(Long employeeId, Long assignmentId) {
        EmployeeAssignment assignment = assignmentRepository
                .findByIdAndEmployee_Id(assignmentId, employeeId)
                .orElseThrow(() -> notFound("Self-assessment not found."));
        requirePublished(assignment);
        return assignment;
    }

    private EmployeeAssignment lockVisibleAssignment(Long employeeId, Long assignmentId) {
        EmployeeAssignment assignment = assignmentRepository
                .findByIdAndEmployee_IdForUpdate(assignmentId, employeeId)
                .orElseThrow(() -> notFound("Self-assessment not found."));
        requirePublished(assignment);
        return assignment;
    }

    /**
     * Only a cycle that is exactly {@code PUBLISHED} is an appraisal the
     * employee may act on. {@code DRAFT} roster rows created in Phase 3D, and
     * any later or future status, are hidden with the same 404 so the API
     * never reveals that such an assignment exists.
     */
    private static void requirePublished(EmployeeAssignment assignment) {
        if (assignment.getCycle() == null
                || assignment.getCycle().getStatus() != CycleStatus.PUBLISHED) {
            throw notFound("Self-assessment not found.");
        }
    }

    private static void requirePendingSelfAssessment(EmployeeAssignment assignment) {
        if (assignment.getStatus() != AssignmentStatus.SELF_ASSESSMENT_PENDING) {
            throw conflict("The self-assessment has already been submitted.");
        }
    }

    private List<EmployeeKpi> orderedKpis(Long assignmentId) {
        return employeeKpiRepository.findByAssignment_IdOrderByIdAsc(assignmentId);
    }

    private Map<Long, SelfAssessment> savedByEmployeeKpi(Long assignmentId) {
        Map<Long, SelfAssessment> saved = new HashMap<>();
        for (SelfAssessment assessment : selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId)) {
            saved.put(assessment.getEmployeeKpi().getId(), assessment);
        }
        return saved;
    }

    /** Rejects decimals instead of letting JSON truncate them silently. */
    private static Short toShortRating(BigDecimal rating) {
        if (rating == null) {
            return null;
        }
        if (rating.stripTrailingZeros().scale() > 0) {
            throw invalid("Self-assessment rating must be a whole number between 1 and 5.");
        }
        short value;
        try {
            value = rating.shortValueExact();
        } catch (ArithmeticException ex) {
            throw invalid("Self-assessment rating must be a whole number between 1 and 5.");
        }
        if (!isValidRating(value)) {
            throw invalid("Self-assessment rating must be a whole number between 1 and 5.");
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

    // ------------------------------------------------------------- responses

    private EmployeeCycleResponse toCycleResponse(EmployeeAssignment assignment) {
        PerformanceCycle cycle = assignment.getCycle();
        return new EmployeeCycleResponse(
                assignment.getId(),
                cycle.getId(),
                cycle.getName(),
                cycle.getDescription(),
                cycle.getStartDate(),
                cycle.getEndDate(),
                cycle.getSelfAssessmentDeadline(),
                cycle.getManagerReviewDeadline(),
                cycle.getHrReviewDeadline(),
                cycle.getStatus().name(),
                cycle.getPublishedAt(),
                assignment.getStatus().name(),
                assignment.getSelfSubmittedAt());
    }

    private SelfAssessmentResponse toResponse(EmployeeAssignment assignment) {
        PerformanceCycle cycle = assignment.getCycle();
        List<EmployeeKpi> kpis = orderedKpis(assignment.getId());
        Map<Long, SelfAssessment> saved = savedByEmployeeKpi(assignment.getId());

        List<SelfAssessmentKpiResponse> items = new ArrayList<>(kpis.size());
        for (EmployeeKpi kpi : kpis) {
            SelfAssessment row = saved.get(kpi.getId());
            items.add(new SelfAssessmentKpiResponse(
                    kpi.getId(),
                    kpi.getKpi() == null ? null : kpi.getKpi().getId(),
                    kpi.getKpiName(),
                    kpi.getKpiDescription(),
                    kpi.getKpiCategory(),
                    kpi.getKpiType() == null ? "MANAGER" : kpi.getKpiType().name(),
                    kpi.getWeightage(),
                    row == null || row.getRating() == null ? null : row.getRating().intValue(),
                    row == null ? null : row.getComment()));
        }

        return new SelfAssessmentResponse(
                assignment.getId(),
                cycle.getId(),
                cycle.getName(),
                cycle.getStatus().name(),
                cycle.getStartDate(),
                cycle.getEndDate(),
                cycle.getSelfAssessmentDeadline(),
                assignment.getStatus().name(),
                assignment.getSelfSubmittedAt(),
                assignment.getStatus() == AssignmentStatus.SELF_ASSESSMENT_PENDING,
                items);
    }

    private SelfAssessmentStatusResponse toStatusResponse(EmployeeAssignment assignment,
                                                          List<EmployeeKpi> kpis,
                                                          Map<Long, SelfAssessment> saved) {
        int rated = 0;
        for (EmployeeKpi kpi : kpis) {
            SelfAssessment row = saved.get(kpi.getId());
            if (row != null && row.getRating() != null) {
                rated++;
            }
        }
        boolean submitted = assignment.getStatus() != AssignmentStatus.SELF_ASSESSMENT_PENDING;
        return new SelfAssessmentStatusResponse(
                assignment.getId(),
                assignment.getCycle().getId(),
                assignment.getCycle().getStatus().name(),
                assignment.getStatus().name(),
                submitted,
                assignment.getSelfSubmittedAt(),
                kpis.size(),
                rated);
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
