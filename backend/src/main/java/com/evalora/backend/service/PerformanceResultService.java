package com.evalora.backend.service;

import com.evalora.backend.dto.BulkResultPublicationResponse;
import com.evalora.backend.dto.PerformanceResultCycleResponse;
import com.evalora.backend.dto.PerformanceResultEmployeeResponse;
import com.evalora.backend.dto.PerformanceResultItemResponse;
import com.evalora.backend.dto.PerformanceResultResponse;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.EmployeeAssignment;
import com.evalora.backend.entity.PerformanceCycle;
import com.evalora.backend.entity.PerformanceResult;
import com.evalora.backend.entity.PerformanceResultItem;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.AssignmentStatus;
import com.evalora.backend.entity.enums.CycleStatus;
import com.evalora.backend.entity.enums.ResultStatus;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.exception.HrErrorType;
import com.evalora.backend.repository.EmployeeAssignmentRepository;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.PerformanceCycleRepository;
import com.evalora.backend.repository.PerformanceResultItemRepository;
import com.evalora.backend.repository.PerformanceResultRepository;
import com.evalora.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PerformanceResultService {

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final PerformanceCycleRepository cycleRepository;
    private final EmployeeAssignmentRepository assignmentRepository;
    private final PerformanceResultRepository resultRepository;
    private final PerformanceResultItemRepository itemRepository;

    public PerformanceResultService(UserRepository userRepository,
                                   EmployeeRepository employeeRepository,
                                   PerformanceCycleRepository cycleRepository,
                                   EmployeeAssignmentRepository assignmentRepository,
                                   PerformanceResultRepository resultRepository,
                                   PerformanceResultItemRepository itemRepository) {
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
        this.cycleRepository = cycleRepository;
        this.assignmentRepository = assignmentRepository;
        this.resultRepository = resultRepository;
        this.itemRepository = itemRepository;
    }

    @Transactional(readOnly = true)
    public List<PerformanceResultResponse> listAwaitingPublication(Long userId) {
        requireUser(userId, Role.HR);
        return resultRepository.findAwaitingPublication(ResultStatus.DRAFT).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Every result of one cycle, published or not, for the HR results browser.
     *
     * <p>HR may read unpublished results, but nothing is recalculated or
     * re-published here. The optional department filter is applied to the
     * employee's stored department; it never changes scores or publication
     * state.</p>
     */
    @Transactional(readOnly = true)
    public List<PerformanceResultResponse> listForCycleForHr(Long userId, Long cycleId, String department) {
        requireUser(userId, Role.HR);
        if (!cycleRepository.existsById(cycleId)) {
            throw notFound("Performance cycle not found.");
        }
        String normalizedDepartment = department == null ? "" : department.trim().toLowerCase();
        return resultRepository.findAllForCycle(cycleId).stream()
                .filter(result -> normalizedDepartment.isEmpty()
                        || (result.getAssignment().getEmployee().getDepartment() != null
                            && result.getAssignment().getEmployee().getDepartment()
                                    .trim().toLowerCase().equals(normalizedDepartment)))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PerformanceResultResponse getForHr(Long userId, Long resultId) {
        requireUser(userId, Role.HR);
        return toResponse(resultRepository.findById(resultId)
                .orElseThrow(() -> notFound("Performance result not found.")));
    }

    @Transactional
    public PerformanceResultResponse publish(Long userId, Long resultId) {
        User hr = requireUser(userId, Role.HR);
        Long assignmentId = resultRepository.findAssignmentIdById(resultId)
                .orElseThrow(() -> notFound("Performance result not found."));
        EmployeeAssignment assignment = assignmentRepository.findByIdForUpdate(assignmentId)
                .orElseThrow(() -> notFound("Performance result not found."));
        PerformanceResult result = resultRepository.findByAssignment_Id(assignmentId)
                .filter(row -> row.getId().equals(resultId))
                .orElseThrow(() -> notFound("Performance result not found."));

        requirePublishable(result, assignment);
        OffsetDateTime publishedAt = now();
        result.setStatus(ResultStatus.PUBLISHED);
        result.setPublishedAt(publishedAt);
        result.setPublishedBy(hr);
        resultRepository.saveAndFlush(result);

        assignment.setStatus(AssignmentStatus.RESULTS_PUBLISHED);
        assignment.setResultsPublishedAt(publishedAt);
        assignmentRepository.saveAndFlush(assignment);
        return toResponse(result);
    }

    /**
     * Publishes every eligible result and skips assignments that are not
     * finalized with a DRAFT result. A failure writing any eligible result
     * rolls back the entire operation.
     */
    @Transactional
    public BulkResultPublicationResponse publishCycle(Long userId, Long cycleId) {
        User hr = requireUser(userId, Role.HR);
        PerformanceCycle cycle = cycleRepository.findByIdForUpdate(cycleId)
                .orElseThrow(() -> notFound("Performance cycle not found."));
        if (cycle.getStatus() != CycleStatus.PUBLISHED) {
            throw conflict("Only results in a published cycle can be published.");
        }

        List<EmployeeAssignment> assignments =
                assignmentRepository.findByCycle_IdForUpdateOrderByIdAsc(cycleId);
        Map<Long, PerformanceResult> resultsByAssignment = new HashMap<>();
        for (PerformanceResult result : resultRepository.findAllForCycle(cycleId)) {
            resultsByAssignment.put(result.getAssignment().getId(), result);
        }

        OffsetDateTime publishedAt = now();
        List<PerformanceResult> changedResults = new ArrayList<>();
        List<EmployeeAssignment> changedAssignments = new ArrayList<>();
        List<Long> publishedResultIds = new ArrayList<>();
        int skippedCount = 0;
        for (EmployeeAssignment assignment : assignments) {
            PerformanceResult result = resultsByAssignment.get(assignment.getId());
            if (!isPublishable(result, assignment)) {
                skippedCount++;
                continue;
            }

            result.setStatus(ResultStatus.PUBLISHED);
            result.setPublishedAt(publishedAt);
            result.setPublishedBy(hr);
            assignment.setStatus(AssignmentStatus.RESULTS_PUBLISHED);
            assignment.setResultsPublishedAt(publishedAt);
            changedResults.add(result);
            changedAssignments.add(assignment);
            publishedResultIds.add(result.getId());
        }

        if (!changedResults.isEmpty()) {
            resultRepository.saveAll(changedResults);
            assignmentRepository.saveAll(changedAssignments);
            resultRepository.flush();
            assignmentRepository.flush();
        }
        return new BulkResultPublicationResponse(
                cycleId, changedResults.size(), skippedCount, List.copyOf(publishedResultIds));
    }

    @Transactional(readOnly = true)
    public List<PerformanceResultResponse> listForEmployee(Long userId) {
        requireProfile(userId, Role.EMPLOYEE);
        return resultRepository.findPublishedForEmployee(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PerformanceResultResponse getForEmployee(Long userId, Long resultId) {
        requireProfile(userId, Role.EMPLOYEE);
        return toResponse(resultRepository.findPublishedByIdForEmployee(resultId, userId)
                .orElseThrow(() -> notFound("Performance result not found.")));
    }

    @Transactional(readOnly = true)
    public List<PerformanceResultResponse> listForManager(Long userId) {
        requireProfile(userId, Role.MANAGER);
        return resultRepository.findPublishedForManager(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PerformanceResultResponse getForManager(Long userId, Long resultId) {
        requireProfile(userId, Role.MANAGER);
        return toResponse(resultRepository.findPublishedByIdForManager(resultId, userId)
                .orElseThrow(() -> notFound("Performance result not found.")));
    }

    private void requirePublishable(PerformanceResult result, EmployeeAssignment assignment) {
        if (!isPublishable(result, assignment)) {
            if (result.getStatus() == ResultStatus.PUBLISHED
                    || assignment.getStatus() == AssignmentStatus.RESULTS_PUBLISHED
                    || assignment.getResultsPublishedAt() != null
                    || result.getPublishedAt() != null
                    || result.getPublishedBy() != null) {
                throw conflict("The performance result has already been published or is inconsistent.");
            }
            throw conflict("The performance result is not eligible for publication.");
        }
    }

    private static boolean isPublishable(PerformanceResult result, EmployeeAssignment assignment) {
        return result != null
                && assignment.getStatus() == AssignmentStatus.COMPLETED
                && assignment.getHrFinalizedAt() != null
                && assignment.getResultsPublishedAt() == null
                && assignment.getCycle() != null
                && assignment.getCycle().getStatus() == CycleStatus.PUBLISHED
                && result.getStatus() == ResultStatus.DRAFT
                && result.getPublishedAt() == null
                && result.getPublishedBy() == null;
    }

    private User requireUser(Long userId, Role role) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> notFound("User not found."));
        if (user.getRole() != role) {
            throw notFound("User not found.");
        }
        return user;
    }

    private Employee requireProfile(Long userId, Role role) {
        requireUser(userId, role);
        return employeeRepository.findByUser_Id(userId)
                .orElseThrow(() -> notFound("User profile not found."));
    }

    private PerformanceResultResponse toResponse(PerformanceResult result) {
        EmployeeAssignment assignment = result.getAssignment();
        Employee employee = assignment.getEmployee();
        PerformanceCycle cycle = assignment.getCycle();
        List<PerformanceResultItemResponse> items = itemRepository
                .findByResult_IdOrderByEmployeeKpi_IdAsc(result.getId()).stream()
                .map(PerformanceResultService::toItemResponse)
                .toList();
        return new PerformanceResultResponse(
                result.getId(),
                assignment.getId(),
                new PerformanceResultEmployeeResponse(
                        employee.getId(),
                        employee.getEmployeeCode(),
                        employee.getFullName(),
                        employee.getDepartment(),
                        employee.getDesignation()),
                new PerformanceResultCycleResponse(
                        cycle.getId(), cycle.getName(), cycle.getStartDate(), cycle.getEndDate()),
                result.getOverallScore(),
                result.getGrade().name(),
                result.getCalculationMode().name(),
                result.getStatus().name(),
                assignment.getHrFinalizedAt(),
                result.getPublishedAt(),
                items);
    }

    private static PerformanceResultItemResponse toItemResponse(PerformanceResultItem item) {
        return new PerformanceResultItemResponse(
                item.getEmployeeKpi().getId(),
                item.getKpiName(),
                item.getWeightage(),
                item.getSelfRating(),
                item.getSelfComment(),
                item.getManagerRating(),
                item.getManagerComment(),
                item.getHrRating(),
                item.getHrComment(),
                item.getFinalScore());
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
}
