package com.evalora.backend.controller;

import com.evalora.backend.dto.ConfigureCycleEmployeesRequest;
import com.evalora.backend.dto.ConfigureCycleKpisRequest;
import com.evalora.backend.dto.CycleEmployeeSelectionRequest;
import com.evalora.backend.dto.CycleKpiSelectionRequest;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.EmployeeAssignment;
import com.evalora.backend.entity.KpiMaster;
import com.evalora.backend.entity.PerformanceCycle;
import com.evalora.backend.entity.PerformanceResult;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.AssignmentStatus;
import com.evalora.backend.entity.enums.CalculationMode;
import com.evalora.backend.entity.enums.CycleStatus;
import com.evalora.backend.entity.enums.PerformanceGrade;
import com.evalora.backend.entity.enums.ResultStatus;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.entity.enums.StaffStatus;
import com.evalora.backend.repository.CycleKpiRepository;
import com.evalora.backend.repository.EmployeeAssignmentRepository;
import com.evalora.backend.repository.EmployeeKpiRepository;
import com.evalora.backend.repository.EmployeeManagerAssignmentRepository;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.HrAssessmentRepository;
import com.evalora.backend.repository.KpiMasterRepository;
import com.evalora.backend.repository.ManagerAssessmentRepository;
import com.evalora.backend.repository.PerformanceCycleRepository;
import com.evalora.backend.repository.PerformanceResultItemRepository;
import com.evalora.backend.repository.PerformanceResultRepository;
import com.evalora.backend.repository.SelfAssessmentRepository;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.security.JwtService;
import com.evalora.backend.service.PerformanceCycleService;
import com.evalora.backend.support.CycleTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_dashboard;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000"
})
class DashboardIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private EmployeeManagerAssignmentRepository managerAssignmentRepository;
    @Autowired private PerformanceCycleRepository cycleRepository;
    @Autowired private CycleKpiRepository cycleKpiRepository;
    @Autowired private EmployeeAssignmentRepository assignmentRepository;
    @Autowired private EmployeeKpiRepository employeeKpiRepository;
    @Autowired private SelfAssessmentRepository selfAssessmentRepository;
    @Autowired private ManagerAssessmentRepository managerAssessmentRepository;
    @Autowired private HrAssessmentRepository hrAssessmentRepository;
    @Autowired private PerformanceResultRepository resultRepository;
    @Autowired private PerformanceResultItemRepository resultItemRepository;
    @Autowired private KpiMasterRepository kpiRepository;
    @Autowired private PerformanceCycleService cycleService;
    @Autowired private JwtService jwtService;
    private User hrUser;
    private Employee manager;
    private Employee otherManager;
    private Employee employeeAwaitingManager;
    private Employee employeeAwaitingHr;
    private Employee employeePublished;
    private Employee employeeCompleted;
    private Employee inactiveEmployee;
    private Employee employeeWithoutCycle;
    private Long cycleId;
    private String hrToken;
    private String managerToken;
    private String otherManagerToken;
    private String employeeToken;
    private String publishedEmployeeToken;
    private String noCycleEmployeeToken;

    @BeforeEach
    void setUp() {
        clearDatabase();

        hrUser = userRepository.saveAndFlush(CycleTestData.user("hr.dashboard@evalora.local", Role.HR));
        manager = profile(Role.MANAGER, "MGR-DASH-1", "Manager One");
        otherManager = profile(Role.MANAGER, "MGR-DASH-2", "Manager Two");
        employeeAwaitingManager = profile(Role.EMPLOYEE, "EMP-DASH-1", "Employee One");
        employeeAwaitingHr = profile(Role.EMPLOYEE, "EMP-DASH-2", "Employee Two");
        employeePublished = profile(Role.EMPLOYEE, "EMP-DASH-3", "Employee Three");
        employeeCompleted = profile(Role.EMPLOYEE, "EMP-DASH-4", "Employee Four");
        inactiveEmployee = profile(Role.EMPLOYEE, "EMP-DASH-5", "Inactive Employee");
        employeeWithoutCycle = profile(Role.EMPLOYEE, "EMP-DASH-6", "Employee Without Cycle");

        employeeAwaitingManager.setManager(manager);
        employeeAwaitingHr.setManager(manager);
        employeePublished.setManager(otherManager);
        employeeCompleted.setManager(manager);
        inactiveEmployee.setStatus(StaffStatus.INACTIVE);
        inactiveEmployee.getUser().setEnabled(false);
        employeeRepository.saveAllAndFlush(List.of(
                employeeAwaitingManager, employeeAwaitingHr, employeePublished, employeeCompleted, inactiveEmployee));
        userRepository.flush();

        hrToken = jwtService.generateToken(hrUser);
        managerToken = jwtService.generateToken(manager.getUser());
        otherManagerToken = jwtService.generateToken(otherManager.getUser());
        employeeToken = jwtService.generateToken(employeeAwaitingManager.getUser());
        publishedEmployeeToken = jwtService.generateToken(employeePublished.getUser());
        noCycleEmployeeToken = jwtService.generateToken(employeeWithoutCycle.getUser());

        KpiMaster kpi = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Dashboard KPI", "Used to seed dashboard assignments.", "Delivery"));
        cycleId = cycleService.create(CycleTestData.request("Dashboard published cycle"), hrUser.getId()).id();
        cycleService.configureKpis(cycleId, new ConfigureCycleKpisRequest(List.of(
                new CycleKpiSelectionRequest(kpi.getId(), null))));
        cycleService.configureEmployees(cycleId, new ConfigureCycleEmployeesRequest(List.of(
                new CycleEmployeeSelectionRequest(employeeAwaitingManager.getId(), manager.getId()),
                new CycleEmployeeSelectionRequest(employeeAwaitingHr.getId(), otherManager.getId()),
                new CycleEmployeeSelectionRequest(employeePublished.getId(), otherManager.getId()),
                new CycleEmployeeSelectionRequest(employeeCompleted.getId(), manager.getId()))));
        employeeAwaitingManager.setManager(manager);
        employeeAwaitingHr.setManager(manager);
        employeePublished.setManager(otherManager);
        employeeCompleted.setManager(manager);
        employeeRepository.saveAllAndFlush(List.of(
                employeeAwaitingManager, employeeAwaitingHr, employeePublished, employeeCompleted));
        cycleService.publish(cycleId);

        setAssignmentStatus(employeeAwaitingManager, AssignmentStatus.MANAGER_REVIEW_PENDING);
        setAssignmentStatus(employeeAwaitingHr, AssignmentStatus.HR_REVIEW_PENDING);
        EmployeeAssignment publishedAssignment =
                setAssignmentStatus(employeePublished, AssignmentStatus.RESULTS_PUBLISHED);
        EmployeeAssignment completedAssignment =
                setAssignmentStatus(employeeCompleted, AssignmentStatus.COMPLETED);
        saveResult(publishedAssignment, ResultStatus.PUBLISHED);
        saveResult(completedAssignment, ResultStatus.DRAFT);

        Long completedCycleId = cycleService.create(
                CycleTestData.request("Dashboard completed cycle"), hrUser.getId()).id();
        PerformanceCycle completedCycle = cycleRepository.findById(completedCycleId).orElseThrow();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        completedCycle.setStatus(CycleStatus.COMPLETED);
        completedCycle.setPublishedAt(now);
        completedCycle.setCompletedAt(now);
        cycleRepository.saveAndFlush(completedCycle);
    }

    @Test
    void hrDashboardCountsActiveStaffCyclesAndWorkflowStates() throws Exception {
        mockMvc.perform(authed(get("/api/hr/dashboard"), hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalActiveEmployees").value(5))
                .andExpect(jsonPath("$.totalActiveManagers").value(2))
                .andExpect(jsonPath("$.totalPerformanceCycles").value(2))
                .andExpect(jsonPath("$.totalPublishedCycles").value(2))
                .andExpect(jsonPath("$.employeesAwaitingSelfAssessment").value(0))
                .andExpect(jsonPath("$.employeesAwaitingManagerReview").value(1))
                .andExpect(jsonPath("$.employeesAwaitingHrReview").value(1))
                .andExpect(jsonPath("$.completedAssessments").value(1))
                .andExpect(jsonPath("$.publishedResults").value(1));
    }

    @Test
    void managerDashboardUsesHistoricalAssignmentOwnerAndCurrentDirectReports() throws Exception {
        mockMvc.perform(authed(get("/api/manager/dashboard")
                        .queryParam("managerId", otherManager.getUser().getId().toString()), managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalActiveDirectReports").value(3))
                .andExpect(jsonPath("$.employeesAwaitingSelfAssessment").value(0))
                .andExpect(jsonPath("$.assessmentsAwaitingManagerReview").value(1))
                .andExpect(jsonPath("$.assessmentsAwaitingHrReview").value(0))
                .andExpect(jsonPath("$.completedAssessments").value(1))
                .andExpect(jsonPath("$.publishedResults").value(0));

        mockMvc.perform(authed(get("/api/manager/dashboard"), otherManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalActiveDirectReports").value(1))
                .andExpect(jsonPath("$.assessmentsAwaitingHrReview").value(1))
                .andExpect(jsonPath("$.publishedResults").value(1));
    }

    @Test
    void employeeDashboardShowsOwnCycleAndOnlyConsistentPublishedResults() throws Exception {
        mockMvc.perform(authed(get("/api/employee/dashboard")
                        .queryParam("employeeId", employeePublished.getId().toString()), employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentCycle.cycleId").value(cycleId))
                .andExpect(jsonPath("$.currentCycle.cycleStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$.currentCycle.assessmentStatus").value("MANAGER_REVIEW_PENDING"))
                .andExpect(jsonPath("$.currentCycle.dueDate").value("2026-12-15"))
                .andExpect(jsonPath("$.latestPublishedResult").doesNotExist());

        mockMvc.perform(authed(get("/api/employee/dashboard"), publishedEmployeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestPublishedResult.score").value(4.25))
                .andExpect(jsonPath("$.latestPublishedResult.cycle.cycleId").value(cycleId))
                .andExpect(jsonPath("$.latestPublishedResult.publishedAt").isNotEmpty());

        mockMvc.perform(authed(get("/api/employee/dashboard"), jwtService.generateToken(employeeCompleted.getUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestPublishedResult").doesNotExist());

        mockMvc.perform(authed(get("/api/employee/dashboard"), noCycleEmployeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentCycle").doesNotExist())
                .andExpect(jsonPath("$.latestPublishedResult").doesNotExist());
    }

    @Test
    void dashboardEndpointsRequireAuthenticationAndCorrectRole() throws Exception {
        mockMvc.perform(get("/api/hr/dashboard")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/manager/dashboard")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/employee/dashboard")).andExpect(status().isUnauthorized());

        mockMvc.perform(authed(get("/api/hr/dashboard"), managerToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/manager/dashboard"), employeeToken)).andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/employee/dashboard"), hrToken)).andExpect(status().isForbidden());
    }

    @Test
    void assessmentStatusListsMatchDashboardCountersAndStayScopedToManager() throws Exception {
        mockMvc.perform(authed(get("/api/hr/assessment-status")
                        .queryParam("status", "MANAGER_REVIEW_PENDING"), hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].employeeFullName").value("Employee One"))
                .andExpect(jsonPath("$[0].managerName").value("Manager One"))
                .andExpect(jsonPath("$[0].cycleName").value("Dashboard published cycle"));

        mockMvc.perform(authed(get("/api/manager/assessment-status")
                        .queryParam("status", "MANAGER_REVIEW_PENDING"), managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].employeeCode").value("EMP-DASH-1"))
                .andExpect(jsonPath("$[1]").doesNotExist());

        mockMvc.perform(authed(get("/api/manager/assessment-status")
                        .queryParam("status", "HR_REVIEW_PENDING"), otherManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].employeeCode").value("EMP-DASH-2"));
    }

    @Test
    void managerDirectReportListContainsOnlyCurrentActiveDirectReports() throws Exception {
        mockMvc.perform(authed(get("/api/manager/direct-reports"), otherManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].employeeCode").value("EMP-DASH-3"))
                .andExpect(jsonPath("$[1]").doesNotExist());
    }

    private Employee profile(Role role, String code, String name) {
        User user = userRepository.saveAndFlush(
                CycleTestData.user(code.toLowerCase() + "@evalora.local", role));
        return employeeRepository.saveAndFlush(CycleTestData.profile(user, code, name));
    }

    private EmployeeAssignment setAssignmentStatus(Employee employee, AssignmentStatus status) {
        EmployeeAssignment assignment = assignmentRepository
                .findByCycle_IdAndEmployee_Id(cycleId, employee.getId())
                .orElseThrow();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        assignment.setStatus(status);
        assignment.setSelfSubmittedAt(now);
        if (status == AssignmentStatus.HR_REVIEW_PENDING
                || status == AssignmentStatus.COMPLETED
                || status == AssignmentStatus.RESULTS_PUBLISHED) {
            assignment.setManagerSubmittedAt(now);
        }
        if (status == AssignmentStatus.COMPLETED || status == AssignmentStatus.RESULTS_PUBLISHED) {
            assignment.setHrFinalizedAt(now);
        }
        if (status == AssignmentStatus.RESULTS_PUBLISHED) {
            assignment.setResultsPublishedAt(now);
        }
        return assignmentRepository.saveAndFlush(assignment);
    }

    private void saveResult(EmployeeAssignment assignment, ResultStatus status) {
        PerformanceResult result = new PerformanceResult();
        result.setAssignment(assignment);
        result.setOverallScore(new BigDecimal("4.25"));
        result.setGrade(PerformanceGrade.VERY_GOOD);
        result.setCalculationMode(CalculationMode.AVERAGE);
        result.setComputedAt(OffsetDateTime.now(ZoneOffset.UTC));
        result.setStatus(status);
        if (status == ResultStatus.PUBLISHED) {
            result.setPublishedAt(assignment.getResultsPublishedAt());
            result.setPublishedBy(hrUser);
        }
        resultRepository.saveAndFlush(result);
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private void clearDatabase() {
        resultItemRepository.deleteAll();
        resultRepository.deleteAll();
        hrAssessmentRepository.deleteAll();
        managerAssessmentRepository.deleteAll();
        selfAssessmentRepository.deleteAll();
        employeeKpiRepository.deleteAll();
        assignmentRepository.deleteAll();
        cycleKpiRepository.deleteAll();
        cycleRepository.deleteAll();
        managerAssignmentRepository.deleteAll();
        employeeRepository.findAll().forEach(profile -> profile.setManager(null));
        employeeRepository.flush();
        employeeRepository.deleteAll();
        kpiRepository.deleteAll();
        userRepository.deleteAll();
    }
}
