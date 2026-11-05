package com.evalora.backend.service;

import com.evalora.backend.dto.EmployeeCurrentCycleResponse;
import com.evalora.backend.dto.EmployeeDashboardResponse;
import com.evalora.backend.dto.EmployeeLatestPublishedResultResponse;
import com.evalora.backend.dto.HrDashboardResponse;
import com.evalora.backend.dto.ManagerDashboardResponse;
import com.evalora.backend.dto.PerformanceResultCycleResponse;
import com.evalora.backend.dto.ReportingManagerResponse;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.EmployeeAssignment;
import com.evalora.backend.entity.PerformanceResult;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.AssignmentStatus;
import com.evalora.backend.entity.enums.CycleStatus;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.entity.enums.StaffStatus;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.exception.HrErrorType;
import com.evalora.backend.repository.EmployeeAssignmentRepository;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.PerformanceCycleRepository;
import com.evalora.backend.repository.PerformanceResultRepository;
import com.evalora.backend.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class DashboardService {

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeAssignmentRepository assignmentRepository;
    private final PerformanceCycleRepository cycleRepository;
    private final PerformanceResultRepository resultRepository;

    public DashboardService(UserRepository userRepository,
                            EmployeeRepository employeeRepository,
                            EmployeeAssignmentRepository assignmentRepository,
                            PerformanceCycleRepository cycleRepository,
                            PerformanceResultRepository resultRepository) {
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.cycleRepository = cycleRepository;
        this.resultRepository = resultRepository;
    }

    @Transactional(readOnly = true)
    public HrDashboardResponse getHrDashboard(Long userId) {
        requireUser(userId, Role.HR);
        Role employeeRole = Role.EMPLOYEE;
        return new HrDashboardResponse(
                employeeRepository.countByRoleAndStatusAndUserEnabled(
                        employeeRole, StaffStatus.ACTIVE, true),
                employeeRepository.countByRoleAndStatusAndUserEnabled(
                        Role.MANAGER, StaffStatus.ACTIVE, true),
                cycleRepository.count(),
                cycleRepository.countByPublishedAtIsNotNull(),
                countByVisibleCycle(AssignmentStatus.SELF_ASSESSMENT_PENDING, employeeRole),
                countByVisibleCycle(AssignmentStatus.MANAGER_REVIEW_PENDING, employeeRole),
                countByVisibleCycle(AssignmentStatus.HR_REVIEW_PENDING, employeeRole),
                assignmentRepository.countByAssignmentStatusAndEmployeeRole(
                        AssignmentStatus.COMPLETED, employeeRole),
                resultRepository.countPublishedResultsVisibleToEmployees());
    }

    @Transactional(readOnly = true)
    public ManagerDashboardResponse getManagerDashboard(Long userId) {
        requireUser(userId, Role.MANAGER);
        Employee manager = requireProfile(userId);
        Role employeeRole = Role.EMPLOYEE;
        return new ManagerDashboardResponse(
                employeeRepository.countDirectReportsByManagerAndRoleAndStatusAndUserEnabled(
                        manager.getId(), employeeRole, StaffStatus.ACTIVE, true),
                countForManagerByVisibleCycle(userId, AssignmentStatus.SELF_ASSESSMENT_PENDING, employeeRole),
                countForManagerByVisibleCycle(userId, AssignmentStatus.MANAGER_REVIEW_PENDING, employeeRole),
                countForManagerByVisibleCycle(userId, AssignmentStatus.HR_REVIEW_PENDING, employeeRole),
                assignmentRepository.countManagerAssignmentsByAssignmentStatus(
                        userId, AssignmentStatus.COMPLETED, employeeRole),
                resultRepository.countPublishedResultsVisibleToManager(userId));
    }

    @Transactional(readOnly = true)
    public EmployeeDashboardResponse getEmployeeDashboard(Long userId) {
        requireUser(userId, Role.EMPLOYEE);
        Employee employee = requireProfile(userId);

        EmployeeCurrentCycleResponse currentCycle = assignmentRepository
                .findFirstByEmployee_User_IdAndCycle_StatusOrderByCycle_IdDesc(
                        userId, CycleStatus.PUBLISHED)
                .map(DashboardService::toCurrentCycleResponse)
                .orElse(null);

        EmployeeLatestPublishedResultResponse latestResult = resultRepository
                .findLatestPublishedForEmployee(userId, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(DashboardService::toLatestResultResponse)
                .orElse(null);

        return new EmployeeDashboardResponse(
                currentCycle, latestResult, toReportingManagerResponse(employee));
    }

    /**
     * The employee's current reporting manager, read from the profile every
     * time. A reassignment by HR is therefore visible on the next dashboard load
     * without any cached copy.
     */
    private static ReportingManagerResponse toReportingManagerResponse(Employee employee) {
        Employee manager = employee.getManager();
        if (manager == null) {
            return ReportingManagerResponse.none();
        }
        return new ReportingManagerResponse(
                manager.getId(), manager.getFullName(), manager.getEmployeeCode());
    }

    private long countByVisibleCycle(AssignmentStatus status, Role employeeRole) {
        return assignmentRepository.countByCycleStatusAndAssignmentStatusAndEmployeeRole(
                CycleStatus.PUBLISHED, status, employeeRole);
    }

    private long countForManagerByVisibleCycle(Long userId, AssignmentStatus status, Role employeeRole) {
        return assignmentRepository.countManagerAssignmentsByCycleStatusAndAssignmentStatus(
                userId, CycleStatus.PUBLISHED, status, employeeRole);
    }

    private void requireUser(Long userId, Role role) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> notFound("User not found."));
        if (user.getRole() != role) {
            throw notFound("User not found.");
        }
    }

    private Employee requireProfile(Long userId) {
        return employeeRepository.findByUser_Id(userId)
                .orElseThrow(() -> notFound("User profile not found."));
    }

    private static EmployeeCurrentCycleResponse toCurrentCycleResponse(EmployeeAssignment assignment) {
        LocalDate dueDate = switch (assignment.getStatus()) {
            case SELF_ASSESSMENT_PENDING -> assignment.getCycle().getSelfAssessmentDeadline();
            case MANAGER_REVIEW_PENDING -> assignment.getCycle().getManagerReviewDeadline();
            case HR_REVIEW_PENDING -> assignment.getCycle().getHrReviewDeadline();
            case COMPLETED, RESULTS_PUBLISHED -> null;
        };
        return new EmployeeCurrentCycleResponse(
                assignment.getCycle().getId(),
                assignment.getCycle().getName(),
                assignment.getCycle().getStatus().name(),
                assignment.getStatus().name(),
                dueDate);
    }

    private static EmployeeLatestPublishedResultResponse toLatestResultResponse(PerformanceResult result) {
        return new EmployeeLatestPublishedResultResponse(
                result.getOverallScore(),
                result.getPublishedAt(),
                new PerformanceResultCycleResponse(
                        result.getAssignment().getCycle().getId(),
                        result.getAssignment().getCycle().getName(),
                        result.getAssignment().getCycle().getStartDate(),
                        result.getAssignment().getCycle().getEndDate()));
    }

    private static HrApiException notFound(String message) {
        return new HrApiException(HrErrorType.NOT_FOUND, message);
    }
}
