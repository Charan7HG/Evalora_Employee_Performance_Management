package com.evalora.backend.service;

import com.evalora.backend.dto.AssessmentStatusItemResponse;
import com.evalora.backend.dto.DirectReportResponse;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.EmployeeAssignment;
import com.evalora.backend.entity.PerformanceCycle;
import com.evalora.backend.entity.enums.AssignmentStatus;
import com.evalora.backend.entity.enums.CycleStatus;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.entity.enums.StaffStatus;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.exception.HrErrorType;
import com.evalora.backend.repository.EmployeeAssignmentRepository;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Destination lists for the HR and manager dashboard assessment widgets.
 *
 * <p>Each list uses the same predicate as the corresponding dashboard counter,
 * so a card's number and the list it opens can never disagree. HR sees every
 * eligible employee; a manager only ever sees assignments whose
 * {@code manager_id} snapshot is their own profile, and only their own active
 * direct reports.</p>
 */
@Service
public class AssessmentStatusService {

    /**
     * The statuses a dashboard widget can drill into. {@code RESULTS_PUBLISHED}
     * is deliberately not one of them: published results have their own
     * results-by-cycle pages.
     */
    private static final List<AssignmentStatus> LISTABLE_STATUSES = List.of(
            AssignmentStatus.SELF_ASSESSMENT_PENDING,
            AssignmentStatus.MANAGER_REVIEW_PENDING,
            AssignmentStatus.HR_REVIEW_PENDING,
            AssignmentStatus.COMPLETED);

    private final EmployeeRepository employeeRepository;
    private final EmployeeAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;

    public AssessmentStatusService(EmployeeRepository employeeRepository,
                                   EmployeeAssignmentRepository assignmentRepository,
                                   UserRepository userRepository) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.userRepository = userRepository;
    }

    // ------------------------------------------------------------------- HR

    @Transactional(readOnly = true)
    public List<AssessmentStatusItemResponse> listForHr(Long userId, AssignmentStatus status) {
        requireRole(userId, Role.HR);
        requireListable(status);

        List<EmployeeAssignment> assignments = status == AssignmentStatus.COMPLETED
                ? assignmentRepository.findAllByAssignmentStatusAndEmployeeRole(
                        status, Role.EMPLOYEE)
                : assignmentRepository.findAllByCycleStatusAndAssignmentStatusAndEmployeeRole(
                        CycleStatus.PUBLISHED, status, Role.EMPLOYEE);
        return assignments.stream().map(AssessmentStatusService::toItem).toList();
    }

    // -------------------------------------------------------------- manager

    @Transactional(readOnly = true)
    public List<AssessmentStatusItemResponse> listForManager(Long userId, AssignmentStatus status) {
        Employee manager = requireManager(userId);
        requireListable(status);

        List<EmployeeAssignment> assignments = status == AssignmentStatus.COMPLETED
                ? assignmentRepository.findManagerAssignmentsByStatusAndEmployeeRole(
                        manager.getUser().getId(), status, Role.EMPLOYEE)
                : assignmentRepository.findManagerAssignmentsByCycleStatusAndStatusAndEmployeeRole(
                        manager.getUser().getId(), CycleStatus.PUBLISHED, status, Role.EMPLOYEE);
        return assignments.stream().map(AssessmentStatusService::toItem).toList();
    }

    @Transactional(readOnly = true)
    public List<DirectReportResponse> listDirectReports(Long userId) {
        Employee manager = requireManager(userId);
        return employeeRepository
                .findDirectReportsByManagerAndRoleAndStatusAndUserEnabled(
                        manager.getId(), Role.EMPLOYEE, StaffStatus.ACTIVE, true)
                .stream()
                .map(employee -> new DirectReportResponse(
                        employee.getId(),
                        employee.getEmployeeCode(),
                        employee.getFullName(),
                        employee.getUser().getEmail(),
                        employee.getDepartment(),
                        employee.getDesignation(),
                        employee.getStatus().name()))
                .toList();
    }

    // -------------------------------------------------------------- helpers

    private Employee requireManager(Long userId) {
        Employee manager = employeeRepository.findByUser_Id(userId)
                .orElseThrow(() -> notFound("Manager profile not found."));
        if (manager.getUser().getRole() != Role.MANAGER) {
            throw notFound("Manager profile not found.");
        }
        return manager;
    }

    private void requireRole(Long userId, Role role) {
        userRepository.findById(userId)
                .filter(user -> user.getRole() == role)
                .orElseThrow(() -> notFound("User profile not found."));
    }

    private static void requireListable(AssignmentStatus status) {
        if (status == null || !LISTABLE_STATUSES.contains(status)) {
            throw new HrApiException(HrErrorType.INVALID_REQUEST,
                    "This assessment status cannot be listed.");
        }
    }

    private static AssessmentStatusItemResponse toItem(EmployeeAssignment assignment) {
        PerformanceCycle cycle = assignment.getCycle();
        Employee employee = assignment.getEmployee();
        Employee manager = assignment.getManager();
        return new AssessmentStatusItemResponse(
                assignment.getId(),
                cycle.getId(),
                cycle.getName(),
                cycle.getStatus().name(),
                assignment.getStatus().name(),
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getFullName(),
                employee.getDepartment(),
                employee.getDesignation(),
                manager == null ? null : manager.getId(),
                manager == null ? null : manager.getFullName(),
                assignment.getSelfSubmittedAt(),
                assignment.getManagerSubmittedAt(),
                assignment.getHrFinalizedAt(),
                dueDate(assignment));
    }

    private static LocalDate dueDate(EmployeeAssignment assignment) {
        PerformanceCycle cycle = assignment.getCycle();
        return switch (assignment.getStatus()) {
            case SELF_ASSESSMENT_PENDING -> cycle.getSelfAssessmentDeadline();
            case MANAGER_REVIEW_PENDING -> cycle.getManagerReviewDeadline();
            case HR_REVIEW_PENDING -> cycle.getHrReviewDeadline();
            case COMPLETED, RESULTS_PUBLISHED -> null;
        };
    }

    private static HrApiException notFound(String message) {
        return new HrApiException(HrErrorType.NOT_FOUND, message);
    }
}
