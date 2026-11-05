package com.evalora.backend.repository;

import com.evalora.backend.entity.CycleKpi;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.EmployeeAssignment;
import com.evalora.backend.entity.EmployeeKpi;
import com.evalora.backend.entity.HrAssessment;
import com.evalora.backend.entity.KpiMaster;
import com.evalora.backend.entity.ManagerAssessment;
import com.evalora.backend.entity.PerformanceCycle;
import com.evalora.backend.entity.PerformanceResult;
import com.evalora.backend.entity.PerformanceResultItem;
import com.evalora.backend.entity.SelfAssessment;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.AssignmentStatus;
import com.evalora.backend.entity.enums.CalculationMode;
import com.evalora.backend.entity.enums.CycleStatus;
import com.evalora.backend.entity.enums.PerformanceGrade;
import com.evalora.backend.entity.enums.ResultStatus;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.entity.enums.StaffStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end persistence test of the whole appraisal chain:
 * cycle -> cycle KPIs -> assignment -> employee KPI snapshots -> ratings
 * -> result -> result items.
 *
 * It also proves the history rules: snapshotted KPI values and stored result
 * items do not change when the master KPI is edited afterwards.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PerformanceWorkflowPersistenceTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private KpiMasterRepository kpiMasterRepository;

    @Autowired
    private PerformanceCycleRepository cycleRepository;

    @Autowired
    private CycleKpiRepository cycleKpiRepository;

    @Autowired
    private EmployeeAssignmentRepository assignmentRepository;

    @Autowired
    private EmployeeKpiRepository employeeKpiRepository;

    @Autowired
    private SelfAssessmentRepository selfAssessmentRepository;

    @Autowired
    private ManagerAssessmentRepository managerAssessmentRepository;

    @Autowired
    private HrAssessmentRepository hrAssessmentRepository;

    @Autowired
    private PerformanceResultRepository resultRepository;

    @Autowired
    private PerformanceResultItemRepository resultItemRepository;

    /** The objects a test needs after the setup has been persisted. */
    private record Scenario(User hrUser,
                            Employee manager,
                            Employee employee,
                            PerformanceCycle cycle,
                            EmployeeAssignment assignment,
                            EmployeeKpi deliveryKpi,
                            EmployeeKpi timelinessKpi,
                            KpiMaster deliveryMaster,
                            KpiMaster timelinessMaster) {
    }

    private User persistUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("$2a$12$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOP");
        user.setRole(role);
        return userRepository.saveAndFlush(user);
    }

    private Employee persistProfile(User user, String code, String name, Employee manager) {
        Employee employee = new Employee();
        employee.setUser(user);
        employee.setEmployeeCode(code);
        employee.setFullName(name);
        employee.setDepartment("Engineering");
        employee.setDesignation("Engineer");
        employee.setJoiningDate(LocalDate.of(2024, 1, 15));
        employee.setManager(manager);
        employee.setStatus(StaffStatus.ACTIVE);
        return employeeRepository.saveAndFlush(employee);
    }

    private void persistCycleKpi(PerformanceCycle cycle, KpiMaster kpi, String weight) {
        CycleKpi cycleKpi = new CycleKpi();
        cycleKpi.setCycle(cycle);
        cycleKpi.setKpi(kpi);
        cycleKpi.setWeightage(new BigDecimal(weight));
        cycleKpiRepository.saveAndFlush(cycleKpi);
    }

    private EmployeeKpi persistEmployeeKpi(EmployeeAssignment assignment, KpiMaster kpi, String name, String weight) {
        EmployeeKpi employeeKpi = new EmployeeKpi();
        employeeKpi.setAssignment(assignment);
        employeeKpi.setKpi(kpi);
        employeeKpi.setKpiName(name);
        employeeKpi.setKpiDescription("Snapshot of " + name);
        employeeKpi.setKpiCategory("Delivery");
        employeeKpi.setWeightage(new BigDecimal(weight));
        return employeeKpiRepository.saveAndFlush(employeeKpi);
    }

    private Scenario createScenario() {
        User hrUser = persistUser("hr@evalora.local", Role.HR);
        persistProfile(hrUser, "HR-001", "Hana HR", null);

        Employee manager = persistProfile(
                persistUser("manager@evalora.local", Role.MANAGER), "MGR-001", "Maya Manager", null);

        Employee employee = persistProfile(
                persistUser("employee@evalora.local", Role.EMPLOYEE), "EMP-001", "Evan Employee", manager);

        KpiMaster deliveryMaster = new KpiMaster();
        deliveryMaster.setName("Delivery Quality");
        deliveryMaster.setCategory("Delivery");
        deliveryMaster.setDescription("Quality of delivered work");
        deliveryMaster.setDefaultWeightage(new BigDecimal("60.00"));
        deliveryMaster = kpiMasterRepository.saveAndFlush(deliveryMaster);

        KpiMaster timelinessMaster = new KpiMaster();
        timelinessMaster.setName("Timeliness");
        timelinessMaster.setCategory("Delivery");
        timelinessMaster.setDescription("Delivered on time");
        timelinessMaster.setDefaultWeightage(new BigDecimal("40.00"));
        timelinessMaster = kpiMasterRepository.saveAndFlush(timelinessMaster);

        PerformanceCycle cycle = new PerformanceCycle();
        cycle.setName("Annual 2026");
        cycle.setDescription("Annual review cycle");
        cycle.setStartDate(LocalDate.of(2026, 1, 1));
        cycle.setEndDate(LocalDate.of(2026, 12, 31));
        cycle.setSelfAssessmentDeadline(LocalDate.of(2026, 12, 15));
        cycle.setManagerReviewDeadline(LocalDate.of(2026, 12, 22));
        cycle.setHrReviewDeadline(LocalDate.of(2026, 12, 31));
        cycle.setStatus(CycleStatus.PUBLISHED);
        cycle.setCreatedBy(hrUser);
        cycle.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        cycle = cycleRepository.saveAndFlush(cycle);

        persistCycleKpi(cycle, deliveryMaster, "60.00");
        persistCycleKpi(cycle, timelinessMaster, "40.00");

        EmployeeAssignment assignment = new EmployeeAssignment();
        assignment.setCycle(cycle);
        assignment.setEmployee(employee);
        assignment.setManager(manager);
        assignment.setStatus(AssignmentStatus.SELF_ASSESSMENT_PENDING);
        assignment = assignmentRepository.saveAndFlush(assignment);

        EmployeeKpi deliveryKpi = persistEmployeeKpi(assignment, deliveryMaster, "Delivery Quality", "60.00");
        EmployeeKpi timelinessKpi = persistEmployeeKpi(assignment, timelinessMaster, "Timeliness", "40.00");

        return new Scenario(hrUser, manager, employee, cycle, assignment,
                deliveryKpi, timelinessKpi, deliveryMaster, timelinessMaster);
    }

    @Test
    void fullWorkflowIsPersisted() {
        Scenario s = createScenario();

        SelfAssessment selfDelivery = new SelfAssessment();
        selfDelivery.setEmployeeKpi(s.deliveryKpi());
        selfDelivery.setRating((short) 4);
        selfDelivery.setComment("I delivered the migration work.");
        selfAssessmentRepository.saveAndFlush(selfDelivery);

        ManagerAssessment managerDelivery = new ManagerAssessment();
        managerDelivery.setEmployeeKpi(s.deliveryKpi());
        managerDelivery.setManager(s.manager());
        managerDelivery.setRating((short) 4);
        managerDelivery.setComment("Good quality overall.");
        managerAssessmentRepository.saveAndFlush(managerDelivery);

        HrAssessment hrDelivery = new HrAssessment();
        hrDelivery.setEmployeeKpi(s.deliveryKpi());
        hrDelivery.setHr(s.hrUser());
        hrDelivery.setRating((short) 5);
        hrDelivery.setComment("Consistently strong.");
        hrAssessmentRepository.saveAndFlush(hrDelivery);

        ManagerAssessment managerTimeliness = new ManagerAssessment();
        managerTimeliness.setEmployeeKpi(s.timelinessKpi());
        managerTimeliness.setManager(s.manager());
        managerTimeliness.setRating((short) 3);
        managerAssessmentRepository.saveAndFlush(managerTimeliness);

        HrAssessment hrTimeliness = new HrAssessment();
        hrTimeliness.setEmployeeKpi(s.timelinessKpi());
        hrTimeliness.setHr(s.hrUser());
        hrTimeliness.setRating((short) 4);
        hrAssessmentRepository.saveAndFlush(hrTimeliness);

        PerformanceResult result = new PerformanceResult();
        result.setAssignment(s.assignment());
        // (4.25 * 60 + 3.25 * 40) / 100 = 3.85
        result.setOverallScore(new BigDecimal("3.85"));
        result.setGrade(PerformanceGrade.VERY_GOOD);
        result.setCalculationMode(CalculationMode.WEIGHTED);
        result.setStatus(ResultStatus.DRAFT);
        result.setComputedAt(OffsetDateTime.now(ZoneOffset.UTC));
        result = resultRepository.saveAndFlush(result);

        PerformanceResultItem deliveryItem = new PerformanceResultItem();
        deliveryItem.setResult(result);
        deliveryItem.setEmployeeKpi(s.deliveryKpi());
        deliveryItem.setKpiName("Delivery Quality");
        deliveryItem.setWeightage(new BigDecimal("60.00"));
        deliveryItem.setSelfRating((short) 4);
        deliveryItem.setSelfComment("I delivered the migration work.");
        deliveryItem.setManagerRating((short) 4);
        deliveryItem.setManagerComment("Good quality overall.");
        deliveryItem.setHrRating((short) 5);
        deliveryItem.setHrComment("Consistently strong.");
        deliveryItem.setFinalScore(new BigDecimal("4.25"));
        resultItemRepository.saveAndFlush(deliveryItem);

        PerformanceResultItem timelinessItem = new PerformanceResultItem();
        timelinessItem.setResult(result);
        timelinessItem.setEmployeeKpi(s.timelinessKpi());
        timelinessItem.setKpiName("Timeliness");
        timelinessItem.setWeightage(new BigDecimal("40.00"));
        timelinessItem.setManagerRating((short) 3);
        timelinessItem.setHrRating((short) 4);
        timelinessItem.setFinalScore(new BigDecimal("3.25"));
        resultItemRepository.saveAndFlush(timelinessItem);

        assertThat(employeeKpiRepository.countByAssignment_Id(s.assignment().getId())).isEqualTo(2);
        assertThat(cycleKpiRepository.findByCycle_Id(s.cycle().getId())).hasSize(2);
        assertThat(selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(s.assignment().getId())).hasSize(1);
        assertThat(managerAssessmentRepository.findByEmployeeKpi_Assignment_Id(s.assignment().getId())).hasSize(2);
        assertThat(hrAssessmentRepository.findByEmployeeKpi_Assignment_Id(s.assignment().getId())).hasSize(2);
        assertThat(resultItemRepository.countByResult_Id(result.getId())).isEqualTo(2);

        PerformanceResult reloaded = resultRepository.findByAssignment_Id(s.assignment().getId()).orElseThrow();
        assertThat(reloaded.getOverallScore()).isEqualByComparingTo("3.85");
        assertThat(reloaded.getOverallScore().scale()).isEqualTo(2);
        assertThat(reloaded.getGrade()).isEqualTo(PerformanceGrade.VERY_GOOD);
        assertThat(reloaded.getStatus()).isEqualTo(ResultStatus.DRAFT);
        assertThat(reloaded.getComputedAt()).isNotNull();
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void snapshotsDoNotChangeWhenTheMasterKpiIsEdited() {
        Scenario s = createScenario();

        PerformanceResult result = new PerformanceResult();
        result.setAssignment(s.assignment());
        result.setOverallScore(new BigDecimal("4.25"));
        result.setGrade(PerformanceGrade.EXCELLENT);
        result.setCalculationMode(CalculationMode.WEIGHTED);
        result.setComputedAt(OffsetDateTime.now(ZoneOffset.UTC));
        result = resultRepository.saveAndFlush(result);

        PerformanceResultItem item = new PerformanceResultItem();
        item.setResult(result);
        item.setEmployeeKpi(s.deliveryKpi());
        item.setKpiName("Delivery Quality");
        item.setWeightage(new BigDecimal("60.00"));
        item.setManagerRating((short) 4);
        item.setHrRating((short) 5);
        item.setFinalScore(new BigDecimal("4.25"));
        resultItemRepository.saveAndFlush(item);

        // HR renames the master KPI and changes its default weight.
        KpiMaster master = kpiMasterRepository.findById(s.deliveryMaster().getId()).orElseThrow();
        master.setName("Delivery Excellence");
        master.setDefaultWeightage(new BigDecimal("70.00"));
        kpiMasterRepository.saveAndFlush(master);

        EmployeeKpi snapshot = employeeKpiRepository.findById(s.deliveryKpi().getId()).orElseThrow();
        assertThat(snapshot.getKpiName()).isEqualTo("Delivery Quality");
        assertThat(snapshot.getWeightage()).isEqualByComparingTo("60.00");

        PerformanceResultItem frozenItem = resultItemRepository.findByResult_Id(result.getId()).getFirst();
        assertThat(frozenItem.getKpiName()).isEqualTo("Delivery Quality");
        assertThat(frozenItem.getWeightage()).isEqualByComparingTo("60.00");
        assertThat(frozenItem.getFinalScore()).isEqualByComparingTo("4.25");
    }

    @Test
    void duplicateAssignmentForTheSameCycleIsRejected() {
        Scenario s = createScenario();

        EmployeeAssignment duplicate = new EmployeeAssignment();
        duplicate.setCycle(s.cycle());
        duplicate.setEmployee(s.employee());
        duplicate.setManager(s.manager());

        assertThatThrownBy(() -> assignmentRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateEmployeeKpiForTheSameAssignmentIsRejected() {
        Scenario s = createScenario();

        EmployeeKpi duplicate = new EmployeeKpi();
        duplicate.setAssignment(s.assignment());
        duplicate.setKpi(s.deliveryMaster());
        duplicate.setKpiName("Delivery Quality");

        assertThatThrownBy(() -> employeeKpiRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateRatingForTheSameEmployeeKpiIsRejected() {
        Scenario s = createScenario();

        HrAssessment first = new HrAssessment();
        first.setEmployeeKpi(s.deliveryKpi());
        first.setHr(s.hrUser());
        first.setRating((short) 4);
        hrAssessmentRepository.saveAndFlush(first);

        HrAssessment second = new HrAssessment();
        second.setEmployeeKpi(s.deliveryKpi());
        second.setHr(s.hrUser());
        second.setRating((short) 5);

        assertThatThrownBy(() -> hrAssessmentRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOneResultPerAssignment() {
        Scenario s = createScenario();

        PerformanceResult first = new PerformanceResult();
        first.setAssignment(s.assignment());
        first.setOverallScore(new BigDecimal("4.00"));
        first.setGrade(PerformanceGrade.EXCELLENT);
        first.setCalculationMode(CalculationMode.AVERAGE);
        first.setComputedAt(OffsetDateTime.now(ZoneOffset.UTC));
        resultRepository.saveAndFlush(first);

        PerformanceResult second = new PerformanceResult();
        second.setAssignment(s.assignment());
        second.setOverallScore(new BigDecimal("4.50"));
        second.setGrade(PerformanceGrade.OUTSTANDING);
        second.setCalculationMode(CalculationMode.AVERAGE);
        second.setComputedAt(OffsetDateTime.now(ZoneOffset.UTC));

        assertThatThrownBy(() -> resultRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
