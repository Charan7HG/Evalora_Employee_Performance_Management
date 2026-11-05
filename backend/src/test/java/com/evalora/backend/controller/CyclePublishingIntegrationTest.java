package com.evalora.backend.controller;

import com.evalora.backend.dto.CycleEmployeeResponse;
import com.evalora.backend.dto.CycleEmployeeSelectionRequest;
import com.evalora.backend.dto.CycleKpiResponse;
import com.evalora.backend.dto.CycleKpiSelectionRequest;
import com.evalora.backend.dto.CycleResponse;
import com.evalora.backend.dto.ConfigureCycleEmployeesRequest;
import com.evalora.backend.dto.ConfigureCycleKpisRequest;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.EmployeeAssignment;
import com.evalora.backend.entity.EmployeeKpi;
import com.evalora.backend.entity.KpiMaster;
import com.evalora.backend.entity.PerformanceCycle;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.CycleStatus;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.entity.enums.StaffStatus;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.repository.CycleKpiRepository;
import com.evalora.backend.repository.EmployeeAssignmentRepository;
import com.evalora.backend.repository.EmployeeKpiRepository;
import com.evalora.backend.repository.EmployeeManagerAssignmentRepository;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.KpiMasterRepository;
import com.evalora.backend.repository.PerformanceCycleRepository;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.service.PerformanceCycleService;
import com.evalora.backend.support.CycleTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3D publication: validation, assignment creation, KPI snapshotting and
 * post-publication immutability.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_cycle_publish;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
class CyclePublishingIntegrationTest {

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
    private PlatformTransactionManager transactionManager;

    private User hrUser;
    private Employee manager;
    private Employee employee;
    private Employee secondEmployee;
    private KpiMaster delivery;
    private KpiMaster quality;
    private Long cycleId;

    @BeforeEach
    void setUp() {
        clearDatabase();

        hrUser = userRepository.saveAndFlush(CycleTestData.user("hr.publish@evalora.local", Role.HR));
        manager = profile(Role.MANAGER, "MGR-PUB", "Mona Manager");
        employee = profile(Role.EMPLOYEE, "EMP-PUB-1", "Eve Employee");
        secondEmployee = profile(Role.EMPLOYEE, "EMP-PUB-2", "Eli Employee");

        delivery = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Delivery Quality", "Meets agreed deadlines.", "Delivery"));
        quality = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Quality", "Work meets the quality bar.", "Quality"));

        cycleId = cycleService.create(CycleTestData.request("Annual 2026"), hrUser.getId()).id();
    }

    // ------------------------------------------------------------------ reject

    @Test
    void publishingWithoutKpisIsRejected() {
        configureEmployees();

        assertThatThrownBy(() -> cycleService.publish(cycleId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("At least one KPI must be configured before publishing.");
        assertThat(cycleService.get(cycleId).status()).isEqualTo("DRAFT");
    }

    @Test
    void publishingWithoutEmployeesIsRejected() {
        configureKpis("60.00", "40.00");

        assertThatThrownBy(() -> cycleService.publish(cycleId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("At least one employee must be assigned before publishing.");
        assertThat(cycleService.get(cycleId).status()).isEqualTo("DRAFT");
    }

    @Test
    void publishingWithInvalidWeightsIsRejected() {
        configureKpis("60.00", "40.00");
        configureEmployees();

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                cycleKpiRepository.findByCycle_IdOrderByIdAsc(cycleId)
                        .forEach(row -> row.setWeightage(new BigDecimal("30.00"))));

        assertThatThrownBy(() -> cycleService.publish(cycleId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("KPI weightages must total exactly 100.00.");
        assertThat(cycleService.get(cycleId).status()).isEqualTo("DRAFT");
    }

    @Test
    void publishingWithAnInactiveEmployeeIsRejected() {
        configureKpis("60.00", "40.00");
        configureEmployees();
        deactivate(employee);

        assertThatThrownBy(() -> cycleService.publish(cycleId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("The selected employee is inactive.");
        assertThat(cycleService.get(cycleId).status()).isEqualTo("DRAFT");
        assertThat(employeeKpiRepository.count()).isZero();
    }

    @Test
    void publishingWithAnInactiveManagerIsRejected() {
        configureKpis("60.00", "40.00");
        configureEmployees();
        deactivate(manager);

        assertThatThrownBy(() -> cycleService.publish(cycleId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("The selected manager is inactive.");
        assertThat(cycleService.get(cycleId).status()).isEqualTo("DRAFT");
        assertThat(employeeKpiRepository.count()).isZero();
    }

    @Test
    void publishingWithAnInactiveKpiIsRejected() {
        configureKpis("60.00", "40.00");
        configureEmployees();

        delivery.setActive(false);
        kpiRepository.saveAndFlush(delivery);

        assertThatThrownBy(() -> cycleService.publish(cycleId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("KPI 'Delivery Quality' is no longer active and cannot be published.");
        assertThat(cycleService.get(cycleId).status()).isEqualTo("DRAFT");
    }

    @Test
    void publishingASelfReviewAssignmentIsRejected() {
        configureKpis("100.00", null);
        // A self assignment cannot be created through the HR API; insert the
        // invalid row directly to prove the publication guard exists as well.
        EmployeeAssignment self = new EmployeeAssignment();
        self.setCycle(cycleRepository.findById(cycleId).orElseThrow());
        self.setEmployee(employee);
        self.setManager(employee);
        assignmentRepository.saveAndFlush(self);

        assertThatThrownBy(() -> cycleService.publish(cycleId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("An employee cannot be assigned to themselves.");
        assertThat(cycleService.get(cycleId).status()).isEqualTo("DRAFT");
    }

    // ---------------------------------------------------------------- success

    @Test
    void publishingCreatesAssignmentsAndSnapshots() {
        configureKpis("60.00", "40.00");
        configureEmployees();

        CycleResponse published = cycleService.publish(cycleId);

        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.publishedAt()).isNotNull();
        assertThat(published.completedAt()).isNull();

        PerformanceCycle storedCycle = cycleRepository.findById(cycleId).orElseThrow();
        assertThat(storedCycle.getStatus()).isEqualTo(CycleStatus.PUBLISHED);
        assertThat(storedCycle.getCompletedAt()).isNull();

        List<EmployeeAssignment> assignments =
                assignmentRepository.findByCycle_IdOrderByEmployee_IdAsc(cycleId);
        assertThat(assignments).hasSize(2);
        assertThat(assignments).allSatisfy(assignment -> {
            assertThat(assignment.getManager().getId()).isEqualTo(manager.getId());
            assertThat(assignment.getStatus().name()).isEqualTo("SELF_ASSESSMENT_PENDING");
            assertThat(assignment.getSelfSubmittedAt()).isNull();
        });

        assertThat(employeeKpiRepository.count()).isEqualTo(4);
        for (EmployeeAssignment assignment : assignments) {
            Map<Long, EmployeeKpi> snapshots = employeeKpiRepository
                    .findByAssignment_Id(assignment.getId())
                    .stream()
                    .collect(Collectors.toMap(snapshot -> snapshot.getKpi().getId(), Function.identity()));

            assertThat(snapshots).hasSize(2);

            EmployeeKpi deliverySnapshot = snapshots.get(delivery.getId());
            assertThat(deliverySnapshot.getKpiName()).isEqualTo("Delivery Quality");
            assertThat(deliverySnapshot.getKpiDescription()).isEqualTo("Meets agreed deadlines.");
            assertThat(deliverySnapshot.getKpiCategory()).isEqualTo("Delivery");
            assertThat(deliverySnapshot.getWeightage()).isEqualByComparingTo("60.00");

            EmployeeKpi qualitySnapshot = snapshots.get(quality.getId());
            assertThat(qualitySnapshot.getKpiName()).isEqualTo("Quality");
            assertThat(qualitySnapshot.getKpiDescription()).isEqualTo("Work meets the quality bar.");
            assertThat(qualitySnapshot.getKpiCategory()).isEqualTo("Quality");
            assertThat(qualitySnapshot.getWeightage()).isEqualByComparingTo("40.00");
        }
    }

    @Test
    void averageModePublicationStoresNullWeights() {
        cycleService.configureKpis(cycleId, new ConfigureCycleKpisRequest(List.of(
                new CycleKpiSelectionRequest(delivery.getId(), null),
                new CycleKpiSelectionRequest(quality.getId(), null))));
        configureEmployees();

        cycleService.publish(cycleId);

        assertThat(employeeKpiRepository.findAll())
                .hasSize(4)
                .allSatisfy(snapshot -> assertThat(snapshot.getWeightage()).isNull());
    }

    // ------------------------------------------------------------ immutability

    @Test
    void publishingTwiceIsRejected() {
        configureKpis("60.00", "40.00");
        configureEmployees();
        cycleService.publish(cycleId);

        assertThatThrownBy(() -> cycleService.publish(cycleId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Only a DRAFT cycle can be published.");
        assertThat(employeeKpiRepository.count()).isEqualTo(4);
    }

    @Test
    void publishedCycleCannotBeUpdatedOrReconfigured() {
        configureKpis("60.00", "40.00");
        configureEmployees();
        cycleService.publish(cycleId);

        assertThatThrownBy(() -> cycleService.update(cycleId, CycleTestData.request("Changed")))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Only a DRAFT cycle can be updated.");

        assertThatThrownBy(() -> cycleService.configureKpis(cycleId,
                new ConfigureCycleKpisRequest(List.of(
                        new CycleKpiSelectionRequest(delivery.getId(), null)))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Only a DRAFT cycle can be modified.");

        assertThatThrownBy(() -> cycleService.configureEmployees(cycleId,
                new ConfigureCycleEmployeesRequest(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), manager.getId())))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Only a DRAFT cycle can be modified.");
    }

    @Test
    void laterMasterKpiChangesDoNotRewriteSnapshots() {
        configureKpis("60.00", "40.00");
        configureEmployees();
        cycleService.publish(cycleId);

        delivery.setName("Delivery Excellence");
        delivery.setDescription("Rewritten description.");
        delivery.setCategory("Rewritten");
        delivery.setDefaultWeightage(new BigDecimal("99.00"));
        kpiRepository.saveAndFlush(delivery);

        List<EmployeeKpi> snapshots = employeeKpiRepository.findAll();
        assertThat(snapshots).hasSize(4);
        assertThat(snapshots).allSatisfy(snapshot -> {
            if (snapshot.getKpi().getId().equals(delivery.getId())) {
                assertThat(snapshot.getKpiName()).isEqualTo("Delivery Quality");
                assertThat(snapshot.getKpiDescription()).isEqualTo("Meets agreed deadlines.");
                assertThat(snapshot.getKpiCategory()).isEqualTo("Delivery");
                assertThat(snapshot.getWeightage()).isEqualByComparingTo("60.00");
            }
        });

        List<CycleKpiResponse> cycleKpis = cycleService.getKpis(cycleId);
        assertThat(cycleKpis).hasSize(2);
        assertThat(cycleKpis.get(0).name()).isEqualTo("Delivery Excellence");
        assertThat(cycleKpis.get(0).weightage()).isEqualByComparingTo("60.00");
    }

    // ----------------------------------------------------------------- helpers

    private void configureKpis(String firstWeight, String secondWeight) {
        List<CycleKpiSelectionRequest> selections = new java.util.ArrayList<>();
        selections.add(new CycleKpiSelectionRequest(delivery.getId(),
                firstWeight == null ? null : new BigDecimal(firstWeight)));
        if (secondWeight != null) {
            selections.add(new CycleKpiSelectionRequest(quality.getId(), new BigDecimal(secondWeight)));
        }
        cycleService.configureKpis(cycleId, new ConfigureCycleKpisRequest(selections));
    }

    private void configureEmployees() {
        List<CycleEmployeeResponse> configured = cycleService.configureEmployees(cycleId,
                new ConfigureCycleEmployeesRequest(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()),
                        new CycleEmployeeSelectionRequest(secondEmployee.getId(), manager.getId()))));
        assertThat(configured).hasSize(2);
    }

    private Employee profile(Role role, String code, String name) {
        User user = userRepository.saveAndFlush(
                CycleTestData.user(code.toLowerCase() + "@evalora.local", role));
        return employeeRepository.saveAndFlush(CycleTestData.profile(user, code, name));
    }

    private void deactivate(Employee profile) {
        profile.setStatus(StaffStatus.INACTIVE);
        profile.getUser().setEnabled(false);
        userRepository.saveAndFlush(profile.getUser());
        employeeRepository.saveAndFlush(profile);
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
