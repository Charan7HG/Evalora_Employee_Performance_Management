package com.evalora.backend.controller;

import com.evalora.backend.dto.CycleEmployeeSelectionRequest;
import com.evalora.backend.dto.CycleRequest;
import com.evalora.backend.dto.CycleResponse;
import com.evalora.backend.dto.ConfigureCycleEmployeesRequest;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.EmployeeAssignment;
import com.evalora.backend.entity.EmployeeManagerAssignment;
import com.evalora.backend.entity.PerformanceCycle;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.CycleStatus;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.entity.enums.StaffStatus;
import com.evalora.backend.repository.CycleKpiRepository;
import com.evalora.backend.repository.EmployeeAssignmentRepository;
import com.evalora.backend.repository.EmployeeKpiRepository;
import com.evalora.backend.repository.EmployeeManagerAssignmentRepository;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.KpiMasterRepository;
import com.evalora.backend.repository.PerformanceCycleRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3D employee-roster rules for a DRAFT cycle, including the separation
 * between a cycle's reviewing-manager snapshot and the reporting-line history.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_cycle_employee;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
class HrCycleEmployeeConfigurationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PerformanceCycleService cycleService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeeManagerAssignmentRepository managerAssignmentRepository;

    @Autowired
    private PerformanceCycleRepository cycleRepository;

    @Autowired
    private CycleKpiRepository cycleKpiRepository;

    @Autowired
    private EmployeeAssignmentRepository assignmentRepository;

    @Autowired
    private EmployeeKpiRepository employeeKpiRepository;

    @Autowired
    private KpiMasterRepository kpiRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private User hrUser;
    private String hrToken;
    private Long cycleId;
    private Employee manager;
    private Employee secondManager;
    private Employee employee;
    private Employee secondEmployee;

    @BeforeEach
    void setUp() {
        clearDatabase();

        hrUser = userRepository.saveAndFlush(CycleTestData.user("hr.cycle.employee@evalora.local", Role.HR));
        hrToken = jwtService.generateToken(hrUser);

        CycleResponse cycle = cycleService.create(CycleTestData.request("Annual 2026"), hrUser.getId());
        cycleId = cycle.id();

        manager = profile(Role.MANAGER, "MGR-001", "Mona Manager");
        secondManager = profile(Role.MANAGER, "MGR-002", "Sam Second");
        employee = profile(Role.EMPLOYEE, "EMP-001", "Eve Employee");
        secondEmployee = profile(Role.EMPLOYEE, "EMP-002", "Eli Employee");
    }

    // ------------------------------------------------------------------ accept

    @Test
    void activeEmployeesCanBeAssignedToActiveManagers() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()),
                        new CycleEmployeeSelectionRequest(secondEmployee.getId(), secondManager.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].employeeId").value(employee.getId()))
                .andExpect(jsonPath("$[0].employeeCode").value("EMP-001"))
                .andExpect(jsonPath("$[0].employeeName").value("Eve Employee"))
                .andExpect(jsonPath("$[0].managerId").value(manager.getId()))
                .andExpect(jsonPath("$[0].managerName").value("Mona Manager"))
                .andExpect(jsonPath("$[0].status").value("SELF_ASSESSMENT_PENDING"))
                .andExpect(jsonPath("$[1].employeeId").value(secondEmployee.getId()));

        List<EmployeeAssignment> stored = assignmentRepository.findByCycle_IdOrderByEmployee_IdAsc(cycleId);
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).getManager().getId()).isEqualTo(manager.getId());
        assertThat(stored.get(0).getStatus().name()).isEqualTo("SELF_ASSESSMENT_PENDING");
        assertThat(stored.get(0).getSelfSubmittedAt()).isNull();
    }

    @Test
    void currentReportingManagerIsUsedWhenManagerIsOmitted() throws Exception {
        linkReportingLine(employee, manager);

        mockMvc.perform(configure(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].managerId").value(manager.getId()));

        assertThat(assignmentRepository.findByCycle_IdAndEmployee_Id(cycleId, employee.getId())
                .orElseThrow().getManager().getId()).isEqualTo(manager.getId());
    }

    @Test
    void explicitManagerChangesOnlyTheCycleSnapshotNotReportingHistory() throws Exception {
        linkReportingLine(employee, manager);
        long historyBefore = managerAssignmentRepository.count();

        mockMvc.perform(configure(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), secondManager.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].managerId").value(secondManager.getId()));

        EmployeeAssignment stored = assignmentRepository
                .findByCycle_IdAndEmployee_Id(cycleId, employee.getId()).orElseThrow();
        assertThat(stored.getManager().getId()).isEqualTo(secondManager.getId());

        assertThat(managerAssignmentRepository.count()).isEqualTo(historyBefore);
        List<EmployeeManagerAssignment> open =
                managerAssignmentRepository.findByEmployee_IdAndEffectiveToIsNull(employee.getId());
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getManager().getId()).isEqualTo(manager.getId());
        assertThat(employeeRepository.findById(employee.getId()).orElseThrow()
                .getManager().getId()).isEqualTo(manager.getId());
    }

    @Test
    void employeeWithoutAReportingManagerCanBeConfiguredExplicitly() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()))))
                .andExpect(status().isOk());
    }

    @Test
    void emptySelectionClearsTheRoster() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()))))
                .andExpect(status().isOk());

        mockMvc.perform(configure(List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        assertThat(assignmentRepository.findByCycle_IdOrderByEmployee_IdAsc(cycleId)).isEmpty();
    }

    @Test
    void fullReplacementRemovesDeselectedEmployees() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()),
                        new CycleEmployeeSelectionRequest(secondEmployee.getId(), manager.getId()))))
                .andExpect(status().isOk());

        mockMvc.perform(configure(List.of(
                        new CycleEmployeeSelectionRequest(secondEmployee.getId(), manager.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].employeeId").value(secondEmployee.getId()));

        assertThat(assignmentRepository.findByCycle_IdOrderByEmployee_IdAsc(cycleId))
                .extracting(row -> row.getEmployee().getId())
                .containsExactly(secondEmployee.getId());
    }

    // ------------------------------------------------------------------ reject

    @Test
    void inactiveEmployeeIsRejected() throws Exception {
        deactivate(employee);

        mockMvc.perform(configure(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The selected employee is inactive."));

        assertThat(assignmentRepository.findByCycle_IdOrderByEmployee_IdAsc(cycleId)).isEmpty();
    }

    @Test
    void disabledEmployeeAccountIsRejected() throws Exception {
        employee.getUser().setEnabled(false);
        userRepository.saveAndFlush(employee.getUser());

        mockMvc.perform(configure(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The selected employee is inactive."));
    }

    @Test
    void inactiveManagerIsRejected() throws Exception {
        deactivate(manager);

        mockMvc.perform(configure(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The selected manager is inactive."));
    }

    @Test
    void managerCannotBeTheAppraisedEmployee() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleEmployeeSelectionRequest(manager.getId(), secondManager.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The selected account is not an employee."));
    }

    @Test
    void hrCannotBeTheAppraisedEmployee() throws Exception {
        Employee hrProfile = profile(Role.HR, "HR-001", "Hana HR");

        mockMvc.perform(configure(List.of(
                        new CycleEmployeeSelectionRequest(hrProfile.getId(), manager.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The selected account is not an employee."));
    }

    @Test
    void employeeCannotReviewThemselves() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), employee.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("An employee cannot be their own manager."));
    }

    @Test
    void duplicateEmployeeIsRejected() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()),
                        new CycleEmployeeSelectionRequest(employee.getId(), secondManager.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("The same employee cannot be assigned twice in one cycle."));
    }

    @Test
    void unknownEmployeeIsRejected() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleEmployeeSelectionRequest(999_999L, manager.getId()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Employee not found."));
    }

    @Test
    void unknownManagerIsRejected() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), 999_999L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Manager not found."));
    }

    @Test
    void employeeWithoutAnyManagerIsRejected() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("A reviewing manager is required for every assigned employee."));
    }

    @Test
    void aNonManagerCannotBeTheReviewer() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), secondEmployee.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The selected account is not a manager."));
    }

    @Test
    void publishedCycleRosterIsImmutable() throws Exception {
        markPublished(cycleId);

        mockMvc.perform(configure(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only a DRAFT cycle can be modified."));
    }

    @Test
    void readingRosterOfAnUnknownCycleReturnsNotFound() throws Exception {
        mockMvc.perform(authed(get("/api/hr/cycles/{id}/employees", 999_999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Performance cycle not found."));
    }

    // ----------------------------------------------------------------- helpers

    private Employee profile(Role role, String code, String name) {
        User user = userRepository.saveAndFlush(
                CycleTestData.user(code.toLowerCase() + "@evalora.local", role));
        return employeeRepository.saveAndFlush(CycleTestData.profile(user, code, name));
    }

    private void linkReportingLine(Employee report, Employee reviewingManager) {
        EmployeeManagerAssignment history = new EmployeeManagerAssignment();
        history.setEmployee(report);
        history.setManager(reviewingManager);
        history.setAssignedBy(hrUser);
        history.setEffectiveFrom(OffsetDateTime.now(ZoneOffset.UTC));
        managerAssignmentRepository.saveAndFlush(history);

        report.setManager(reviewingManager);
        employeeRepository.saveAndFlush(report);
    }

    private void deactivate(Employee profile) {
        profile.setStatus(StaffStatus.INACTIVE);
        profile.getUser().setEnabled(false);
        userRepository.saveAndFlush(profile.getUser());
        employeeRepository.saveAndFlush(profile);
    }

    private MockHttpServletRequestBuilder configure(List<CycleEmployeeSelectionRequest> selections) {
        return authed(put("/api/hr/cycles/{id}/employees", cycleId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new ConfigureCycleEmployeesRequest(selections)));
    }

    private void markPublished(Long id) {
        PerformanceCycle cycle = cycleRepository.findById(id).orElseThrow();
        cycle.setStatus(CycleStatus.PUBLISHED);
        cycle.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        cycleRepository.saveAndFlush(cycle);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + hrToken);
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private void clearDatabase() {
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
