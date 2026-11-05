package com.evalora.backend.service;

import com.evalora.backend.dto.HrAssessmentEntryRequest;
import com.evalora.backend.dto.HrAssessmentKpiResponse;
import com.evalora.backend.dto.HrAssessmentResponse;
import com.evalora.backend.dto.HrAssessmentStatusResponse;
import com.evalora.backend.dto.HrEmployeeSummary;
import com.evalora.backend.dto.HrQueueItemResponse;
import com.evalora.backend.dto.SaveHrAssessmentDraftRequest;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.EmployeeAssignment;
import com.evalora.backend.entity.EmployeeKpi;
import com.evalora.backend.entity.HrAssessment;
import com.evalora.backend.entity.ManagerAssessment;
import com.evalora.backend.entity.PerformanceCycle;
import com.evalora.backend.entity.PerformanceResult;
import com.evalora.backend.entity.PerformanceResultItem;
import com.evalora.backend.entity.SelfAssessment;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.AssignmentStatus;
import com.evalora.backend.entity.enums.CalculationMode;
import com.evalora.backend.entity.enums.CycleStatus;
import com.evalora.backend.entity.enums.KpiType;
import com.evalora.backend.entity.enums.ResultStatus;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.exception.HrErrorType;
import com.evalora.backend.repository.EmployeeAssignmentRepository;
import com.evalora.backend.repository.EmployeeKpiRepository;
import com.evalora.backend.repository.HrAssessmentRepository;
import com.evalora.backend.repository.ManagerAssessmentRepository;
import com.evalora.backend.repository.PerformanceResultItemRepository;
import com.evalora.backend.repository.PerformanceResultRepository;
import com.evalora.backend.repository.SelfAssessmentRepository;
import com.evalora.backend.repository.UserRepository;
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
 * HR-side review of an assignment whose employee and manager have both
 * submitted: the pending queue, the three-way review screen, draft ratings and
 * the one-way finalization.
 *
 * <p>Authorization is role-based only - HR reviews every eligible employee, so
 * there is no ownership predicate. The caller's identity comes from the
 * database-backed principal ({@link com.evalora.backend.security.EvaloraPrincipal}),
 * never from the request, and is stored as {@code hr_assessments.hr_id}.</p>
 *
 * <p>An assignment is visible only while its cycle is exactly
 * {@code PUBLISHED}, both the self-assessment and manager assessment have been
 * submitted, and the row has reached HR review. A missing, unpublished,
 * unsubmitted or not-yet-at-HR-review assignment all produce the same 404.
 * Finalized rows stay readable but are not editable. Draft saves and
 * submissions take a pessimistic write lock on the assignment row before
 * checking its status, so they serialise per assignment: two submissions
 * cannot both observe {@code HR_REVIEW_PENDING}, and a draft can never be
 * written after a submission commits.</p>
 *
 * <p>Submission records {@code hr_finalized_at}, moves the assignment to
 * {@code COMPLETED} and writes the single DRAFT performance result - one header
 * plus one item per assigned KPI - all in the same transaction. The result is
 * calculated from two independent categories: manager-assessed KPIs are scored
 * from the manager rating and contribute 75 %; HR-assessed KPIs are scored from
 * the average of the manager and HR ratings, {@code (manager + HR) / 2}, and
 * contribute 25 %. The employee self-rating is copied for context but never
 * scored. The result stays unpublished ({@code published_at} is null) and the
 * employee and manager assessment rows are never modified.</p>
 */
@Service
public class HrAssessmentService {

    private static final short MIN_RATING = 1;
    private static final short MAX_RATING = 5;

    /**
     * Statuses at (or after) HR review. HR can still read its own review after
     * finalization while the cycle remains published; only rows that have not
     * reached HR review are hidden.
     */
    private static final Set<AssignmentStatus> VISIBLE_STATUSES = EnumSet.of(
            AssignmentStatus.HR_REVIEW_PENDING,
            AssignmentStatus.COMPLETED,
            AssignmentStatus.RESULTS_PUBLISHED);

    private final UserRepository userRepository;
    private final EmployeeAssignmentRepository assignmentRepository;
    private final EmployeeKpiRepository employeeKpiRepository;
    private final SelfAssessmentRepository selfAssessmentRepository;
    private final ManagerAssessmentRepository managerAssessmentRepository;
    private final HrAssessmentRepository hrAssessmentRepository;
    private final PerformanceResultRepository performanceResultRepository;
    private final PerformanceResultItemRepository performanceResultItemRepository;

    public HrAssessmentService(UserRepository userRepository,
                               EmployeeAssignmentRepository assignmentRepository,
                               EmployeeKpiRepository employeeKpiRepository,
                               SelfAssessmentRepository selfAssessmentRepository,
                               ManagerAssessmentRepository managerAssessmentRepository,
                               HrAssessmentRepository hrAssessmentRepository,
                               PerformanceResultRepository performanceResultRepository,
                               PerformanceResultItemRepository performanceResultItemRepository) {
        this.userRepository = userRepository;
        this.assignmentRepository = assignmentRepository;
        this.employeeKpiRepository = employeeKpiRepository;
        this.selfAssessmentRepository = selfAssessmentRepository;
        this.managerAssessmentRepository = managerAssessmentRepository;
        this.hrAssessmentRepository = hrAssessmentRepository;
        this.performanceResultRepository = performanceResultRepository;
        this.performanceResultItemRepository = performanceResultItemRepository;
    }

    // ---------------------------------------------------------------- queue

    @Transactional(readOnly = true)
    public List<HrQueueItemResponse> listPending(Long userId) {
        requireHr(userId);
        return assignmentRepository
                .findHrReviewQueue(CycleStatus.PUBLISHED, AssignmentStatus.HR_REVIEW_PENDING)
                .stream()
                .map(this::toQueueItem)
                .toList();
    }

    // --------------------------------------------------------------- detail

    @Transactional(readOnly = true)
    public HrAssessmentResponse get(Long userId, Long assignmentId) {
        requireHr(userId);
        EmployeeAssignment assignment = requireVisibleAssignment(assignmentId);
        return toResponse(assignment);
    }

    @Transactional(readOnly = true)
    public HrAssessmentStatusResponse getStatus(Long userId, Long assignmentId) {
        requireHr(userId);
        EmployeeAssignment assignment = requireVisibleAssignment(assignmentId);
        List<EmployeeKpi> kpis = orderedKpis(assignmentId);
        return toStatusResponse(assignment, kpis, savedByEmployeeKpi(assignmentId));
    }

    // ---------------------------------------------------------------- draft

    /**
     * Creates or updates HR's saved draft. The complete request is validated
     * before any row is written, so a bad entry never leaves a partial save
     * behind. Status and timestamps are never touched.
     */
    @Transactional
    public HrAssessmentResponse saveDraft(Long userId,
                                          Long assignmentId,
                                          SaveHrAssessmentDraftRequest request) {
        User hr = requireHr(userId);
        EmployeeAssignment assignment = lockVisibleAssignment(assignmentId);
        requireEditable(assignment);

        List<EmployeeKpi> kpis = orderedKpis(assignmentId);
        Map<Long, EmployeeKpi> kpisById = new HashMap<>();
        for (EmployeeKpi kpi : kpis) {
            kpisById.put(kpi.getId(), kpi);
        }

        List<HrAssessmentEntryRequest> entries =
                request == null || request.kpis() == null ? List.of() : request.kpis();

        Set<Long> seen = new HashSet<>();
        List<ValidatedEntry> validated = new ArrayList<>(entries.size());
        for (HrAssessmentEntryRequest entry : entries) {
            if (entry == null || entry.employeeKpiId() == null) {
                throw invalid("Employee KPI id is required.");
            }
            EmployeeKpi kpi = kpisById.get(entry.employeeKpiId());
            if (kpi == null) {
                throw invalid("The selected KPI does not belong to this assessment.");
            }
            // Phase 5D: manager KPIs belong to the reviewing manager. HR only
            // rates the HR-assessed KPIs and can never write a manager rating.
            if (kpiTypeOf(kpi) != KpiType.HR) {
                throw invalid("HR can only assess HR-assessed KPIs.");
            }
            if (!seen.add(kpi.getId())) {
                throw invalid("The same KPI cannot be rated twice in one request.");
            }
            validated.add(new ValidatedEntry(kpi,
                    toShortRating(entry.rating()),
                    normalizeOptional(entry.comment())));
        }

        Map<Long, HrAssessment> saved = savedByEmployeeKpi(assignmentId);
        List<HrAssessment> changed = new ArrayList<>(validated.size());
        for (ValidatedEntry entry : validated) {
            HrAssessment row = saved.get(entry.kpi().getId());
            if (row == null) {
                // Do not materialise a row that carries no information yet.
                if (entry.rating() == null && entry.comment() == null) {
                    continue;
                }
                row = new HrAssessment();
                row.setEmployeeKpi(entry.kpi());
            }
            // HR can rotate between reviewers, so the stored author follows
            // the last editor; the row itself is never duplicated.
            row.setHr(hr);
            row.setRating(entry.rating());
            row.setComment(entry.comment());
            changed.add(row);
        }
        if (!changed.isEmpty()) {
            hrAssessmentRepository.saveAll(changed);
            hrAssessmentRepository.flush();
        }

        return toResponse(assignment);
    }

    // --------------------------------------------------------------- submit

    /**
     * Finalizes HR's assessment and generates the official result.
     *
     * <p>Every assigned KPI must have a manager rating from 1 to 5 and every
     * HR-assessed KPI must also have an HR rating from 1 to 5; a missing
     * rating is rejected, never treated as zero. Manager-assessed KPIs are
     * averaged from the manager ratings. Each HR-assessed KPI is first reduced
     * to {@code (managerRating + hrRating) / 2}, then those combined ratings
     * are averaged with the same weighted/unweighted rules. The overall score
     * is {@code manager * 0.75 + hr * 0.25}; a cycle with only one category is
     * scored from that category alone. The KPI weights are validated again
     * here, even though cycle configuration already rejects invalid totals.
     * The assignment moves to {@code COMPLETED}, {@code hr_finalized_at} is
     * recorded, and one DRAFT result header plus one item per KPI are written -
     * all in the same transaction, so any failure leaves no partial result and
     * no status change.</p>
     *
     * <p>This is not publication: {@code results_published_at} stays null and
     * no employee or manager endpoint can see the result yet.</p>
     */
    @Transactional
    public HrAssessmentStatusResponse submit(Long userId, Long assignmentId) {
        requireHr(userId);
        EmployeeAssignment assignment = lockVisibleAssignment(assignmentId);
        requireEditable(assignment);

        // A result while the assignment is still pending is inconsistent. Do
        // not overwrite it and do not create a second one.
        if (performanceResultRepository.findByAssignment_Id(assignmentId).isPresent()) {
            throw conflict("A performance result already exists for this assessment.");
        }

        List<EmployeeKpi> kpis = orderedKpis(assignmentId);
        if (kpis.isEmpty()) {
            throw conflict("No KPIs are assigned to this assessment.");
        }
        Map<Long, SelfAssessment> self = selfByEmployeeKpi(assignmentId);
        Map<Long, ManagerAssessment> manager = managerByEmployeeKpi(assignmentId);
        Map<Long, HrAssessment> saved = savedByEmployeeKpi(assignmentId);

        List<BigDecimal> managerRatings = new ArrayList<>();
        List<BigDecimal> managerWeights = new ArrayList<>();
        List<BigDecimal> hrRatings = new ArrayList<>();
        List<BigDecimal> hrWeights = new ArrayList<>();
        Map<Long, BigDecimal> finalScoresByKpi = new HashMap<>();
        for (EmployeeKpi kpi : kpis) {
            KpiType kpiType = kpiTypeOf(kpi);
            ManagerAssessment managerRow = manager.get(kpi.getId());
            HrAssessment hrRow = saved.get(kpi.getId());

            // Every KPI, including an HR-assessed one, needs the manager rating
            // the manager supplied during manager review. A missing rating is
            // rejected instead of being treated as zero.
            if (managerRow == null || managerRow.getRating() == null
                    || !isValidRating(managerRow.getRating())) {
                throw conflict(
                        "Every assigned KPI must have a manager rating from 1 to 5 before submitting.");
            }

            if (kpiType == KpiType.MANAGER) {
                managerRatings.add(BigDecimal.valueOf(managerRow.getRating()));
                managerWeights.add(kpi.getWeightage());
                finalScoresByKpi.put(kpi.getId(), PerformanceScoreCalculator.kpiScore(
                        kpiType, managerRow.getRating(), null));
            } else {
                // An HR KPI also needs HR's own rating. Its combined score is
                // the average of the manager and HR ratings.
                if (hrRow == null || hrRow.getRating() == null
                        || !isValidRating(hrRow.getRating())) {
                    throw conflict(
                            "Every HR-assessed KPI must have an HR rating from 1 to 5 before submitting.");
                }
                BigDecimal combined = PerformanceScoreCalculator.kpiScore(
                        kpiType, managerRow.getRating(), hrRow.getRating());
                hrRatings.add(combined);
                hrWeights.add(kpi.getWeightage());
                finalScoresByKpi.put(kpi.getId(), combined);
            }
        }

        // A cycle's weights still have to be all-or-nothing and add up to 100
        // across both categories; that is a cycle-validity rule, not a
        // per-category one.
        CalculationMode mode = PerformanceScoreCalculator.resolveMode(kpis);
        BigDecimal managerCategory = managerRatings.isEmpty()
                ? null
                : PerformanceScoreCalculator.categoryScore(managerRatings, managerWeights);
        BigDecimal hrCategory = hrRatings.isEmpty()
                ? null
                : PerformanceScoreCalculator.categoryScore(hrRatings, hrWeights);
        BigDecimal overallScore =
                PerformanceScoreCalculator.overallScore(managerCategory, hrCategory);

        PerformanceResult result = persistResult(assignment, mode, overallScore);
        persistResultItems(result, kpis, finalScoresByKpi, self, manager, saved);

        assignment.setHrFinalizedAt(now());
        assignment.setStatus(AssignmentStatus.COMPLETED);
        assignmentRepository.saveAndFlush(assignment);

        return toStatusResponse(assignment, kpis, saved);
    }

    /**
     * Writes the header as DRAFT. The publish columns stay null, so the result
     * is invisible until a later publication phase.
     */
    private PerformanceResult persistResult(EmployeeAssignment assignment,
                                            CalculationMode mode,
                                            BigDecimal overallScore) {
        PerformanceResult result = new PerformanceResult();
        result.setAssignment(assignment);
        result.setOverallScore(overallScore);
        result.setGrade(PerformanceScoreCalculator.gradeFor(overallScore));
        result.setCalculationMode(mode);
        result.setStatus(ResultStatus.DRAFT);
        result.setComputedAt(now());
        return performanceResultRepository.saveAndFlush(result);
    }

    /**
     * Copies the frozen KPI values and the three ratings into the permanent
     * breakdown. Self rating and comment are stored for context only; the
     * final score was calculated from manager and HR ratings alone. The
     * manager rating and comment are retained for every KPI, including an
     * HR-assessed KPI, because they are half of that KPI's combined score.
     */
    private void persistResultItems(PerformanceResult result,
                                    List<EmployeeKpi> kpis,
                                    Map<Long, BigDecimal> finalScoresByKpi,
                                    Map<Long, SelfAssessment> self,
                                    Map<Long, ManagerAssessment> manager,
                                    Map<Long, HrAssessment> hr) {
        List<PerformanceResultItem> items = new ArrayList<>(kpis.size());
        for (EmployeeKpi kpi : kpis) {
            SelfAssessment selfRow = self.get(kpi.getId());
            ManagerAssessment managerRow = manager.get(kpi.getId());
            HrAssessment hrRow = hr.get(kpi.getId());
            boolean hrAssessed = kpiTypeOf(kpi) == KpiType.HR;

            PerformanceResultItem item = new PerformanceResultItem();
            item.setResult(result);
            item.setEmployeeKpi(kpi);
            item.setKpiName(kpi.getKpiName());
            item.setWeightage(kpi.getWeightage());
            item.setSelfRating(selfRow == null ? null : selfRow.getRating());
            item.setSelfComment(selfRow == null ? null : selfRow.getComment());
            item.setManagerRating(managerRow == null ? null : managerRow.getRating());
            item.setManagerComment(managerRow == null ? null : managerRow.getComment());
            item.setHrRating(hrAssessed && hrRow != null ? hrRow.getRating() : null);
            item.setHrComment(hrAssessed && hrRow != null ? hrRow.getComment() : null);
            item.setFinalScore(finalScoresByKpi.get(kpi.getId()));
            items.add(item);
        }
        performanceResultItemRepository.saveAll(items);
        performanceResultItemRepository.flush();
    }

    // -------------------------------------------------------------- helpers

    /**
     * The authenticated HR account. The role check is redundant with the
     * security chain but keeps direct service calls honest.
     */
    private User requireHr(Long userId) {
        User hr = userRepository.findById(userId)
                .orElseThrow(() -> notFound("HR profile not found."));
        if (hr.getRole() != Role.HR) {
            throw notFound("HR profile not found.");
        }
        return hr;
    }

    private EmployeeAssignment requireVisibleAssignment(Long assignmentId) {
        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> notFound("HR assessment not found."));
        requireVisible(assignment);
        return assignment;
    }

    private EmployeeAssignment lockVisibleAssignment(Long assignmentId) {
        EmployeeAssignment assignment = assignmentRepository.findByIdForUpdate(assignmentId)
                .orElseThrow(() -> notFound("HR assessment not found."));
        requireVisible(assignment);
        return assignment;
    }

    /**
     * Only a published cycle whose employee and manager have both submitted,
     * and whose assignment has reached HR review, is visible to HR. A DRAFT
     * (or future) cycle, an assignment still waiting for the employee or
     * manager, an assignment that has not reached HR review and an unknown id
     * all produce the same 404.
     */
    private static void requireVisible(EmployeeAssignment assignment) {
        if (assignment.getCycle() == null
                || assignment.getCycle().getStatus() != CycleStatus.PUBLISHED
                || assignment.getSelfSubmittedAt() == null
                || assignment.getManagerSubmittedAt() == null
                || !VISIBLE_STATUSES.contains(assignment.getStatus())) {
            throw notFound("HR assessment not found.");
        }
    }

    private static void requireEditable(EmployeeAssignment assignment) {
        if (assignment.getStatus() != AssignmentStatus.HR_REVIEW_PENDING) {
            throw conflict("The HR assessment has already been submitted.");
        }
    }

    private List<EmployeeKpi> orderedKpis(Long assignmentId) {
        return employeeKpiRepository.findByAssignment_IdOrderByIdAsc(assignmentId);
    }

    private Map<Long, HrAssessment> savedByEmployeeKpi(Long assignmentId) {
        Map<Long, HrAssessment> saved = new HashMap<>();
        for (HrAssessment assessment : hrAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId)) {
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

    private Map<Long, ManagerAssessment> managerByEmployeeKpi(Long assignmentId) {
        Map<Long, ManagerAssessment> manager = new HashMap<>();
        for (ManagerAssessment assessment
                : managerAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId)) {
            manager.put(assessment.getEmployeeKpi().getId(), assessment);
        }
        return manager;
    }

    /** Rejects decimals instead of letting JSON truncate them silently. */
    private static Short toShortRating(BigDecimal rating) {
        if (rating == null) {
            return null;
        }
        if (rating.stripTrailingZeros().scale() > 0) {
            throw invalid("HR assessment rating must be a whole number between 1 and 5.");
        }
        short value;
        try {
            value = rating.shortValueExact();
        } catch (ArithmeticException ex) {
            throw invalid("HR assessment rating must be a whole number between 1 and 5.");
        }
        if (!isValidRating(value)) {
            throw invalid("HR assessment rating must be a whole number between 1 and 5.");
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

    private HrQueueItemResponse toQueueItem(EmployeeAssignment assignment) {
        Employee employee = assignment.getEmployee();
        PerformanceCycle cycle = assignment.getCycle();
        return new HrQueueItemResponse(
                assignment.getId(),
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getFullName(),
                employee.getDepartment(),
                employee.getDesignation(),
                cycle.getId(),
                cycle.getName(),
                assignment.getManagerSubmittedAt(),
                assignment.getStatus().name());
    }

    private HrAssessmentResponse toResponse(EmployeeAssignment assignment) {
        PerformanceCycle cycle = assignment.getCycle();
        Employee employee = assignment.getEmployee();
        List<EmployeeKpi> kpis = orderedKpis(assignment.getId());
        Map<Long, SelfAssessment> self = selfByEmployeeKpi(assignment.getId());
        Map<Long, ManagerAssessment> manager = managerByEmployeeKpi(assignment.getId());
        Map<Long, HrAssessment> hr = savedByEmployeeKpi(assignment.getId());

        List<HrAssessmentKpiResponse> items = new ArrayList<>(kpis.size());
        for (EmployeeKpi kpi : kpis) {
            SelfAssessment selfRow = self.get(kpi.getId());
            ManagerAssessment managerRow = manager.get(kpi.getId());
            HrAssessment hrRow = hr.get(kpi.getId());
            items.add(new HrAssessmentKpiResponse(
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
                    managerRow == null ? null : managerRow.getComment(),
                    hrRow == null || hrRow.getRating() == null ? null : hrRow.getRating().intValue(),
                    hrRow == null ? null : hrRow.getComment()));
        }

        return new HrAssessmentResponse(
                assignment.getId(),
                cycle.getId(),
                cycle.getName(),
                cycle.getStatus().name(),
                cycle.getStartDate(),
                cycle.getEndDate(),
                cycle.getHrReviewDeadline(),
                assignment.getStatus().name(),
                assignment.getSelfSubmittedAt(),
                assignment.getManagerSubmittedAt(),
                assignment.getHrFinalizedAt(),
                assignment.getStatus() == AssignmentStatus.HR_REVIEW_PENDING,
                new HrEmployeeSummary(
                        employee.getId(),
                        employee.getEmployeeCode(),
                        employee.getFullName(),
                        employee.getDepartment(),
                        employee.getDesignation()),
                items);
    }

    private HrAssessmentStatusResponse toStatusResponse(EmployeeAssignment assignment,
                                                        List<EmployeeKpi> kpis,
                                                        Map<Long, HrAssessment> saved) {
        int hrKpiCount = 0;
        int rated = 0;
        for (EmployeeKpi kpi : kpis) {
            // HR's progress is measured against the KPIs HR owns only.
            if (kpiTypeOf(kpi) != KpiType.HR) {
                continue;
            }
            hrKpiCount++;
            HrAssessment row = saved.get(kpi.getId());
            if (row != null && row.getRating() != null) {
                rated++;
            }
        }
        boolean submitted = assignment.getStatus() != AssignmentStatus.HR_REVIEW_PENDING;
        return new HrAssessmentStatusResponse(
                assignment.getId(),
                assignment.getCycle().getId(),
                assignment.getStatus().name(),
                hrKpiCount,
                rated,
                submitted,
                assignment.getHrFinalizedAt());
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
