package com.evalora.backend.service;

import com.evalora.backend.dto.ConfigureCycleEmployeesRequest;
import com.evalora.backend.dto.ConfigureCycleKpisRequest;
import com.evalora.backend.dto.CycleEmployeeSelectionRequest;
import com.evalora.backend.dto.CycleKpiSelectionRequest;
import com.evalora.backend.dto.HrAssessmentEntryRequest;
import com.evalora.backend.dto.ManagerAssessmentEntryRequest;
import com.evalora.backend.dto.SaveHrAssessmentDraftRequest;
import com.evalora.backend.dto.SaveManagerAssessmentDraftRequest;
import com.evalora.backend.dto.SaveSelfAssessmentDraftRequest;
import com.evalora.backend.dto.SelfAssessmentEntryRequest;
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
import com.evalora.backend.entity.enums.KpiType;
import com.evalora.backend.entity.enums.PerformanceGrade;
import com.evalora.backend.entity.enums.ResultStatus;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.exception.HrErrorType;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Phase 3H result generation at the service boundary.
 *
 * <p>Proves the weighted and average paths, frozen snapshot usage, missing
 * rating and invalid weight rejection, unpublished persistence, duplicate
 * handling and transaction rollback. H2 is used only for the JPA schema and
 * transaction behaviour; PostgreSQL is not exercised here.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_result_generation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000"
})
class PerformanceResultGenerationIntegrationTest {

    @Autowired
    private HrAssessmentService hrAssessmentService;

    @Autowired
    private SelfAssessmentService selfAssessmentService;

    @Autowired
    private ManagerAssessmentService managerAssessmentService;

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
    private SelfAssessmentRepository selfAssessmentRepository;

    @Autowired
    private ManagerAssessmentRepository managerAssessmentRepository;

    @Autowired
    private HrAssessmentRepository hrAssessmentRepository;

    @Autowired
    private PerformanceResultRepository resultRepository;

    @MockitoSpyBean
    private PerformanceResultItemRepository resultItemRepository;

    @Autowired
    private KpiMasterRepository kpiRepository;

    private User hrUser;
    private Employee manager;
    private Employee employee;
    private KpiMaster deliveryMaster;
    private KpiMaster qualityMaster;

    private Long cycleId;
    private Long assignmentId;
    private Long firstEmployeeKpiId;
    private Long secondEmployeeKpiId;

    @BeforeEach
    void setUp() {
        clearDatabase();

        hrUser = userRepository.saveAndFlush(
                CycleTestData.user("hr.result@evalora.local", Role.HR));
        manager = profile(Role.MANAGER, "MGR-RESULT", "Mona Manager");
        employee = profile(Role.EMPLOYEE, "EMP-RESULT", "Eve Employee");

        deliveryMaster = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Delivery Quality", "Meets agreed deadlines.", "Delivery"));
        deliveryMaster.setKpiType(KpiType.MANAGER);
        deliveryMaster = kpiRepository.saveAndFlush(deliveryMaster);
        qualityMaster = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Quality", "Work meets the quality bar.", "Quality"));
        qualityMaster.setKpiType(KpiType.HR);
        qualityMaster = kpiRepository.saveAndFlush(qualityMaster);

        cycleId = cycleService.create(CycleTestData.request("Annual 2026"), hrUser.getId()).id();
        cycleService.configureKpis(cycleId, new ConfigureCycleKpisRequest(List.of(
                new CycleKpiSelectionRequest(deliveryMaster.getId(), new BigDecimal("60.00")),
                new CycleKpiSelectionRequest(qualityMaster.getId(), new BigDecimal("40.00")))));
        cycleService.configureEmployees(cycleId, new ConfigureCycleEmployeesRequest(List.of(
                new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()))));
        cycleService.publish(cycleId);

        assignmentId = assignmentRepository.findByCycle_IdOrderByEmployee_IdAsc(cycleId)
                .getFirst().getId();
        List<EmployeeKpi> kpis = employeeKpiRepository.findByAssignment_IdOrderByIdAsc(assignmentId);
        firstEmployeeKpiId = kpis.get(0).getId();
        secondEmployeeKpiId = kpis.get(1).getId();

        submitSelfAssessment(4, 5);
        submitManagerAssessment(4);
    }

    @AfterEach
    void resetSpy() {
        Mockito.reset(resultItemRepository);
    }

    // ------------------------------------------------------- weighted result

    @Test
    void weightedSubmissionWritesOneHeaderAndOneItemPerKpi() {
        saveHrDraft(5);

        hrAssessmentService.submit(hrUser.getId(), assignmentId);

        assertThat(resultRepository.count()).isEqualTo(1);
        PerformanceResult result = resultRepository.findByAssignment_Id(assignmentId).orElseThrow();
        // Manager category 4.00; HR KPI combines manager 4 and HR 5 = 4.50.
        // Final = 4.00 * 0.75 + 4.50 * 0.25 = 4.125, rounded to 4.13.
        assertThat(result.getOverallScore()).isEqualByComparingTo("4.13");
        assertThat(result.getCalculationMode()).isEqualTo(CalculationMode.WEIGHTED);
        assertThat(result.getGrade()).isEqualTo(PerformanceGrade.EXCELLENT);
        assertThat(result.getStatus()).isEqualTo(ResultStatus.DRAFT);
        assertThat(result.getComputedAt()).isNotNull();
        assertThat(result.getPublishedAt()).isNull();
        assertThat(result.getPublishedBy()).isNull();

        Map<Long, PerformanceResultItem> items = itemsByKpi(result);
        assertThat(items).hasSize(2);

        PerformanceResultItem first = items.get(firstEmployeeKpiId);
        assertThat(first.getKpiName()).isEqualTo("Delivery Quality");
        assertThat(first.getWeightage()).isEqualByComparingTo("60.00");
        assertThat(first.getManagerRating()).isEqualTo((short) 4);
        assertThat(first.getHrRating()).isNull();
        assertThat(first.getFinalScore()).isEqualByComparingTo("4.00");
        assertThat(first.getSelfRating()).isEqualTo((short) 4);
        assertThat(first.getSelfComment()).isEqualTo("Self one.");

        PerformanceResultItem second = items.get(secondEmployeeKpiId);
        assertThat(second.getKpiName()).isEqualTo("Quality");
        assertThat(second.getWeightage()).isEqualByComparingTo("40.00");
        assertThat(second.getManagerRating()).isEqualTo((short) 4);
        assertThat(second.getHrRating()).isEqualTo((short) 5);
        assertThat(second.getFinalScore()).isEqualByComparingTo("4.50");
        assertThat(second.getSelfRating()).isEqualTo((short) 5);

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(assignment.getHrFinalizedAt()).isNotNull();
        assertThat(assignment.getResultsPublishedAt()).isNull();
        assertThat(cycleRepository.findById(cycleId).orElseThrow().getStatus())
                .isEqualTo(CycleStatus.PUBLISHED);
    }

    @Test
    void averageSubmissionUsesTheArithmeticMeanWhenEveryWeightIsNull() {
        setWeights(null, null);
        saveHrDraft(5);

        hrAssessmentService.submit(hrUser.getId(), assignmentId);

        PerformanceResult result = resultRepository.findByAssignment_Id(assignmentId).orElseThrow();
        assertThat(result.getCalculationMode()).isEqualTo(CalculationMode.AVERAGE);
        // HR KPI combines manager 4 and HR 5 = 4.50; final is rounded to 4.13.
        assertThat(result.getOverallScore()).isEqualByComparingTo("4.13");
        assertThat(result.getGrade()).isEqualTo(PerformanceGrade.EXCELLENT);
        assertThat(result.getStatus()).isEqualTo(ResultStatus.DRAFT);

        Map<Long, PerformanceResultItem> items = itemsByKpi(result);
        assertThat(items.get(firstEmployeeKpiId).getWeightage()).isNull();
        assertThat(items.get(secondEmployeeKpiId).getWeightage()).isNull();
        assertThat(items.get(firstEmployeeKpiId).getFinalScore()).isEqualByComparingTo("4.00");
        assertThat(items.get(secondEmployeeKpiId).getFinalScore()).isEqualByComparingTo("4.50");
    }

    @Test
    void managerOnlyCycleUsesTheManagerCategoryWithoutRedistribution() {
        EmployeeKpi second = employeeKpiRepository.findById(secondEmployeeKpiId).orElseThrow();
        second.setKpiType(KpiType.MANAGER);
        employeeKpiRepository.saveAndFlush(second);

        setManagerRating(secondEmployeeKpiId, (short) 5);

        hrAssessmentService.submit(hrUser.getId(), assignmentId);

        PerformanceResult result = resultRepository.findByAssignment_Id(assignmentId).orElseThrow();
        // Manager category = (4*60 + 5*40) / 100 = 4.40. There is no HR
        // category, so the overall score is that category, not 4.40 * 0.75.
        assertThat(result.getCalculationMode()).isEqualTo(CalculationMode.WEIGHTED);
        assertThat(result.getOverallScore()).isEqualByComparingTo("4.40");
        assertThat(result.getGrade()).isEqualTo(PerformanceGrade.EXCELLENT);

        Map<Long, PerformanceResultItem> items = itemsByKpi(result);
        assertThat(items.get(firstEmployeeKpiId).getHrRating()).isNull();
        assertThat(items.get(secondEmployeeKpiId).getManagerRating()).isEqualTo((short) 5);
        assertThat(items.get(secondEmployeeKpiId).getHrRating()).isNull();
    }

    @Test
    void hrOnlyCycleUsesTheHrCategoryWithoutRedistribution() {
        EmployeeKpi first = employeeKpiRepository.findById(firstEmployeeKpiId).orElseThrow();
        first.setKpiType(KpiType.HR);
        employeeKpiRepository.saveAndFlush(first);

        HrAssessment hrRow = new HrAssessment();
        hrRow.setEmployeeKpi(first);
        hrRow.setHr(hrUser);
        hrRow.setRating((short) 4);
        hrRow.setComment("First HR KPI.");
        hrAssessmentRepository.saveAndFlush(hrRow);
        saveHrDraft(5);

        hrAssessmentService.submit(hrUser.getId(), assignmentId);

        PerformanceResult result = resultRepository.findByAssignment_Id(assignmentId).orElseThrow();
        // HR ratings combine with manager 4: weighted category=(4*60+4.5*40)/100=4.20.
        // There is no manager category, so the overall score is that category.
        assertThat(result.getCalculationMode()).isEqualTo(CalculationMode.WEIGHTED);
        assertThat(result.getOverallScore()).isEqualByComparingTo("4.20");
        assertThat(result.getGrade()).isEqualTo(PerformanceGrade.EXCELLENT);

        Map<Long, PerformanceResultItem> items = itemsByKpi(result);
        assertThat(items.get(firstEmployeeKpiId).getHrRating()).isEqualTo((short) 4);
        assertThat(items.get(firstEmployeeKpiId).getManagerRating()).isEqualTo((short) 4);
        assertThat(items.get(secondEmployeeKpiId).getManagerRating()).isEqualTo((short) 4);
    }

    @Test
    void selfRatingNeverContributesToFinalScores() {
        List<SelfAssessment> selfRows =
                selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId);
        for (SelfAssessment row : selfRows) {
            row.setRating((short) 1);
            row.setComment("Self disagrees.");
        }
        selfAssessmentRepository.saveAllAndFlush(selfRows);
        saveHrDraft(5);

        hrAssessmentService.submit(hrUser.getId(), assignmentId);

        PerformanceResult result = resultRepository.findByAssignment_Id(assignmentId).orElseThrow();
        assertThat(result.getOverallScore()).isEqualByComparingTo("4.13");
        Map<Long, PerformanceResultItem> items = itemsByKpi(result);
        assertThat(items.get(firstEmployeeKpiId).getSelfRating()).isEqualTo((short) 1);
        assertThat(items.get(firstEmployeeKpiId).getFinalScore()).isEqualByComparingTo("4.00");
        assertThat(items.get(secondEmployeeKpiId).getSelfRating()).isEqualTo((short) 1);
        assertThat(items.get(secondEmployeeKpiId).getFinalScore()).isEqualByComparingTo("4.50");
    }

    @Test
    void masterKpiChangesDoNotRewriteTheGeneratedResult() {
        saveHrDraft(5);
        hrAssessmentService.submit(hrUser.getId(), assignmentId);

        deliveryMaster.setName("Renamed KPI");
        deliveryMaster.setDescription("Rewritten description.");
        deliveryMaster.setCategory("Rewritten");
        deliveryMaster.setDefaultWeightage(new BigDecimal("1.00"));
        kpiRepository.saveAndFlush(deliveryMaster);

        PerformanceResult result = resultRepository.findByAssignment_Id(assignmentId).orElseThrow();
        PerformanceResultItem first = itemsByKpi(result).get(firstEmployeeKpiId);
        assertThat(first.getKpiName()).isEqualTo("Delivery Quality");
        assertThat(first.getWeightage()).isEqualByComparingTo("60.00");
        assertThat(first.getFinalScore()).isEqualByComparingTo("4.00");

        EmployeeKpi snapshot = employeeKpiRepository.findById(firstEmployeeKpiId).orElseThrow();
        assertThat(snapshot.getKpiName()).isEqualTo("Delivery Quality");
        assertThat(snapshot.getWeightage()).isEqualByComparingTo("60.00");
    }

    // ---------------------------------------------------- invalid scoring input

    @Test
    void missingManagerRatingFailsWithoutCreatingAResult() {
        setManagerRating(firstEmployeeKpiId, null);
        saveHrDraft(5);

        assertThatThrownBy(() -> hrAssessmentService.submit(hrUser.getId(), assignmentId))
                .isInstanceOf(HrApiException.class)
                .satisfies(failure -> assertThat(((HrApiException) failure).getType())
                        .isEqualTo(HrErrorType.CONFLICT))
                .hasMessage("Every assigned KPI must have a manager rating from 1 to 5 before submitting.");

        assertNoResultAndStillPending();
    }

    @Test
    void missingHrRatingFailsWithoutCreatingAResult() {
        assertThatThrownBy(() -> hrAssessmentService.submit(hrUser.getId(), assignmentId))
                .isInstanceOf(HrApiException.class)
                .satisfies(failure -> assertThat(((HrApiException) failure).getType())
                        .isEqualTo(HrErrorType.CONFLICT))
                .hasMessage("Every HR-assessed KPI must have an HR rating from 1 to 5 before submitting.");

        assertNoResultAndStillPending();
    }

    @Test
    void partiallyConfiguredWeightsAreRejected() {
        setWeights(new BigDecimal("60.00"), null);
        saveHrDraft(5);

        expectConflict("Every KPI must have a weightage, or none of them.");
    }

    @Test
    void weightsThatDoNotAddUpTo100AreRejected() {
        setWeights(new BigDecimal("60.00"), new BigDecimal("30.00"));
        saveHrDraft(5);

        expectConflict("KPI weightages must add up to exactly 100.00.");
    }

    @Test
    void zeroWeightIsRejected() {
        setWeights(BigDecimal.ZERO, new BigDecimal("100.00"));
        saveHrDraft(5);

        expectConflict("Each KPI weightage must be greater than 0 and at most 100.");
    }

    @Test
    void negativeWeightIsRejected() {
        setWeights(new BigDecimal("-1.00"), new BigDecimal("101.00"));
        saveHrDraft(5);

        expectConflict("Each KPI weightage must be greater than 0 and at most 100.");
    }

    @Test
    void weightGreaterThan100IsRejected() {
        setWeights(new BigDecimal("101.00"), new BigDecimal("100.00"));
        saveHrDraft(5);

        expectConflict("Each KPI weightage must be greater than 0 and at most 100.");
    }

    @Test
    void emptyKpiCollectionIsRejected() {
        managerAssessmentRepository.deleteAll(
                managerAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId));
        managerAssessmentRepository.flush();
        selfAssessmentRepository.deleteAll(
                selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId));
        selfAssessmentRepository.flush();
        List<EmployeeKpi> kpis = employeeKpiRepository.findByAssignment_IdOrderByIdAsc(assignmentId);
        employeeKpiRepository.deleteAll(kpis);
        employeeKpiRepository.flush();

        assertThatThrownBy(() -> hrAssessmentService.submit(hrUser.getId(), assignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("No KPIs are assigned to this assessment.");

        assertNoResultAndStillPending();
    }

    @Test
    void existingResultIsNeverOverwrittenOrDuplicated() {
        saveHrDraft(5);

        PerformanceResult existing = new PerformanceResult();
        existing.setAssignment(assignmentRepository.findById(assignmentId).orElseThrow());
        existing.setOverallScore(new BigDecimal("3.00"));
        existing.setGrade(PerformanceGrade.GOOD);
        existing.setCalculationMode(CalculationMode.AVERAGE);
        existing.setStatus(ResultStatus.DRAFT);
        existing.setComputedAt(OffsetDateTime.now(ZoneOffset.UTC));
        resultRepository.saveAndFlush(existing);

        assertThatThrownBy(() -> hrAssessmentService.submit(hrUser.getId(), assignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("A performance result already exists for this assessment.");

        assertThat(resultRepository.count()).isEqualTo(1);
        PerformanceResult stored = resultRepository.findByAssignment_Id(assignmentId).orElseThrow();
        assertThat(stored.getOverallScore()).isEqualByComparingTo("3.00");
        assertThat(resultItemRepository.count()).isZero();
        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.HR_REVIEW_PENDING);
        assertThat(assignment.getHrFinalizedAt()).isNull();
    }

    // -------------------------------------------------------- atomic rollback

    @Test
    void resultItemFailureRollsBackTheHeaderAndAssignment() {
        saveHrDraft(5);
        doThrow(new IllegalStateException("Simulated result item failure"))
                .when(resultItemRepository).saveAll(any());

        assertThatThrownBy(() -> hrAssessmentService.submit(hrUser.getId(), assignmentId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Simulated result item failure");

        assertThat(resultRepository.count()).isZero();
        assertThat(resultItemRepository.count()).isZero();
        assertNoResultAndStillPending();
        assertThat(hrAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId)).hasSize(1);

        // The failed attempt left no partial state, so a retry succeeds.
        Mockito.reset(resultItemRepository);
        hrAssessmentService.submit(hrUser.getId(), assignmentId);
        PerformanceResult result = resultRepository.findByAssignment_Id(assignmentId).orElseThrow();
        assertThat(resultItemRepository.findByResult_Id(result.getId())).hasSize(2);
        assertThat(assignmentRepository.findById(assignmentId).orElseThrow().getStatus())
                .isEqualTo(AssignmentStatus.COMPLETED);
    }

    // ----------------------------------------------------------------- helpers

    private void expectConflict(String message) {
        assertThatThrownBy(() -> hrAssessmentService.submit(hrUser.getId(), assignmentId))
                .isInstanceOf(HrApiException.class)
                .satisfies(failure -> assertThat(((HrApiException) failure).getType())
                        .isEqualTo(HrErrorType.CONFLICT))
                .hasMessage(message);
        assertNoResultAndStillPending();
    }

    private void assertNoResultAndStillPending() {
        assertThat(resultRepository.count()).isZero();
        assertThat(resultItemRepository.count()).isZero();
        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.HR_REVIEW_PENDING);
        assertThat(assignment.getHrFinalizedAt()).isNull();
        assertThat(assignment.getResultsPublishedAt()).isNull();
    }

    private void saveHrDraft(int hrRating) {
        hrAssessmentService.saveDraft(hrUser.getId(), assignmentId,
                new SaveHrAssessmentDraftRequest(List.of(
                        new HrAssessmentEntryRequest(secondEmployeeKpiId,
                                new BigDecimal(hrRating), "HR quality."))));
    }

    private void setWeights(BigDecimal first, BigDecimal second) {
        List<EmployeeKpi> kpis = employeeKpiRepository.findByAssignment_IdOrderByIdAsc(assignmentId);
        kpis.get(0).setWeightage(first);
        kpis.get(1).setWeightage(second);
        employeeKpiRepository.saveAllAndFlush(kpis);
    }

    private void setManagerRating(Long employeeKpiId, Short rating) {
        ManagerAssessment row = managerAssessmentRepository.findByEmployeeKpi_Id(employeeKpiId)
                .orElseThrow();
        row.setRating(rating);
        managerAssessmentRepository.saveAndFlush(row);
    }

    private Map<Long, PerformanceResultItem> itemsByKpi(PerformanceResult result) {
        return resultItemRepository.findByResult_Id(result.getId()).stream()
                .collect(Collectors.toMap(item -> item.getEmployeeKpi().getId(), Function.identity()));
    }

    private void submitSelfAssessment(int firstRating, int secondRating) {
        selfAssessmentService.saveDraft(employee.getUser().getId(), assignmentId,
                new SaveSelfAssessmentDraftRequest(List.of(
                        new SelfAssessmentEntryRequest(firstEmployeeKpiId,
                                new BigDecimal(firstRating), "Self one."),
                        new SelfAssessmentEntryRequest(secondEmployeeKpiId,
                                new BigDecimal(secondRating), "Self two."))));
        selfAssessmentService.submit(employee.getUser().getId(), assignmentId);
    }

    private void submitManagerAssessment(int managerRating) {
        managerAssessmentService.saveDraft(manager.getUser().getId(), assignmentId,
                new SaveManagerAssessmentDraftRequest(List.of(
                        new ManagerAssessmentEntryRequest(firstEmployeeKpiId,
                                new BigDecimal(managerRating), "Manager delivery."),
                        new ManagerAssessmentEntryRequest(secondEmployeeKpiId,
                                new BigDecimal(managerRating), "Manager quality."))));
        managerAssessmentService.submit(manager.getUser().getId(), assignmentId);
    }

    private Employee profile(Role role, String code, String name) {
        User user = userRepository.saveAndFlush(
                CycleTestData.user(code.toLowerCase() + "@evalora.local", role));
        return employeeRepository.saveAndFlush(CycleTestData.profile(user, code, name));
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
