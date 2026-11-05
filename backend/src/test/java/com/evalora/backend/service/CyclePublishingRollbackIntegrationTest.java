package com.evalora.backend.service;

import com.evalora.backend.dto.CycleEmployeeSelectionRequest;
import com.evalora.backend.dto.CycleKpiSelectionRequest;
import com.evalora.backend.dto.ConfigureCycleEmployeesRequest;
import com.evalora.backend.dto.ConfigureCycleKpisRequest;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.KpiMaster;
import com.evalora.backend.entity.PerformanceCycle;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.CycleStatus;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.repository.CycleKpiRepository;
import com.evalora.backend.repository.EmployeeAssignmentRepository;
import com.evalora.backend.repository.EmployeeKpiRepository;
import com.evalora.backend.repository.EmployeeManagerAssignmentRepository;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.KpiMasterRepository;
import com.evalora.backend.repository.PerformanceCycleRepository;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.support.CycleTestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Proves that a failure while writing the employee-KPI snapshots rolls the
 * whole publication back: the cycle stays DRAFT and no snapshot survives.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_cycle_rollback;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
class CyclePublishingRollbackIntegrationTest {

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

    @MockitoSpyBean
    private EmployeeKpiRepository employeeKpiRepository;

    @Autowired
    private KpiMasterRepository kpiRepository;

    private Long cycleId;

    @BeforeEach
    void setUp() {
        clearDatabase();

        User hrUser = userRepository.saveAndFlush(
                CycleTestData.user("hr.rollback@evalora.local", Role.HR));
        Employee manager = profile(Role.MANAGER, "MGR-RB", "Mona Manager");
        Employee employee = profile(Role.EMPLOYEE, "EMP-RB-1", "Eve Employee");
        Employee secondEmployee = profile(Role.EMPLOYEE, "EMP-RB-2", "Eli Employee");

        KpiMaster delivery = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Delivery Quality", "Meets agreed deadlines.", "Delivery"));
        KpiMaster quality = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Quality", "Work meets the quality bar.", "Quality"));

        cycleId = cycleService.create(CycleTestData.request("Annual 2026"), hrUser.getId()).id();
        cycleService.configureKpis(cycleId, new ConfigureCycleKpisRequest(List.of(
                new CycleKpiSelectionRequest(delivery.getId(), new BigDecimal("60.00")),
                new CycleKpiSelectionRequest(quality.getId(), new BigDecimal("40.00")))));
        cycleService.configureEmployees(cycleId, new ConfigureCycleEmployeesRequest(List.of(
                new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()),
                new CycleEmployeeSelectionRequest(secondEmployee.getId(), manager.getId()))));
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(employeeKpiRepository);
    }

    @Test
    void snapshotFailureRollsBackTheWholePublication() {
        doThrow(new IllegalStateException("Simulated snapshot failure"))
                .when(employeeKpiRepository).saveAll(any());

        assertThatThrownBy(() -> cycleService.publish(cycleId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Simulated snapshot failure");

        PerformanceCycle cycle = cycleRepository.findById(cycleId).orElseThrow();
        assertThat(cycle.getStatus()).isEqualTo(CycleStatus.DRAFT);
        assertThat(cycle.getPublishedAt()).isNull();
        assertThat(employeeKpiRepository.count()).isZero();

        // The draft roster itself was configured before publication and must
        // remain, still waiting to be published.
        assertThat(assignmentRepository.findByCycle_IdOrderByEmployee_IdAsc(cycleId)).hasSize(2);
        assertThat(assignmentRepository.findByCycle_IdOrderByEmployee_IdAsc(cycleId))
                .allSatisfy(assignment -> assertThat(assignment.getStatus().name())
                        .isEqualTo("SELF_ASSESSMENT_PENDING"));
    }

    @Test
    void aCycleCanBePublishedAfterATransientSnapshotFailure() {
        doThrow(new IllegalStateException("Simulated snapshot failure"))
                .when(employeeKpiRepository).saveAll(any());
        assertThatThrownBy(() -> cycleService.publish(cycleId))
                .isInstanceOf(IllegalStateException.class);

        Mockito.reset(employeeKpiRepository);

        var published = cycleService.publish(cycleId);
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(employeeKpiRepository.count()).isEqualTo(4);
    }

    private Employee profile(Role role, String code, String name) {
        User user = userRepository.saveAndFlush(
                CycleTestData.user(code.toLowerCase() + "@evalora.local", role));
        return employeeRepository.saveAndFlush(CycleTestData.profile(user, code, name));
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
