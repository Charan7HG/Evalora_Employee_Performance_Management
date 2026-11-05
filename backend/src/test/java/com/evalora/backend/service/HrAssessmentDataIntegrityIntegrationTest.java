package com.evalora.backend.service;

import com.evalora.backend.dto.ConfigureCycleEmployeesRequest;
import com.evalora.backend.dto.ConfigureCycleKpisRequest;
import com.evalora.backend.dto.CycleEmployeeResponse;
import com.evalora.backend.dto.CycleEmployeeSelectionRequest;
import com.evalora.backend.dto.CycleKpiSelectionRequest;
import com.evalora.backend.dto.HrAssessmentEntryRequest;
import com.evalora.backend.dto.HrAssessmentResponse;
import com.evalora.backend.dto.HrAssessmentStatusResponse;
import com.evalora.backend.dto.ManagerAssessmentEntryRequest;
import com.evalora.backend.dto.SaveManagerAssessmentDraftRequest;
import com.evalora.backend.dto.SaveHrAssessmentDraftRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3G data integrity at the service boundary.
 *
 * <p>Proves the properties the HTTP suite cannot see: exactly one row per
 * assigned KPI, the editing HR account recorded on every row, only-{@code
 * PUBLISHED} visibility, the {@code COMPLETED} finalization transition, result
 * generation, master KPI snapshot stability, and the unchanged employee
 * self-assessment and manager assessment tables.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_hr_assessment_data;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000"
})
class HrAssessmentDataIntegrityIntegrationTest {

    @Autowired
    private HrAssessmentService hrAssessmentService;

    @Autowired
    private ManagerAssessmentService managerAssessmentService;

    @Autowired
    private SelfAssessmentService selfAssessmentService;

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

    @Autowired
    private PerformanceResultItemRepository resultItemRepository;

    @Autowired
    private KpiMasterRepository kpiRepository;

    private User hrUser;
    private User otherHrUser;
    private Employee manager;
    private Employee employee;
    private Employee otherEmployee;
    private KpiMaster deliveryMaster;
    private KpiMaster qualityMaster;

    private Long cycleId;
    private Long assignmentId;
    private Long otherAssignmentId;
    private Long draftAssignmentId;
    private Long firstEmployeeKpiId;
    private Long secondEmployeeKpiId;
    private Long otherEmployeeKpiId;
    private Long otherEmployeeKpiId2;

    @BeforeEach
    void setUp() {
        clearDatabase();

        hrUser = userRepository.saveAndFlush(
                CycleTestData.user("hr.assessor.data@evalora.local", Role.HR));
        otherHrUser = userRepository.saveAndFlush(
                CycleTestData.user("hr.assessor.two@evalora.local", Role.HR));
        manager = profile(Role.MANAGER, "MGR-HR-DATA", "Mona Manager");
        employee = profile(Role.EMPLOYEE, "EMP-HR-DATA", "Eve Employee");
        otherEmployee = profile(Role.EMPLOYEE, "EMP-HR-DATA-2", "Oscar Other");

        deliveryMaster = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Delivery Quality", "Meets agreed deadlines.", "Delivery"));
        deliveryMaster.setKpiType(KpiType.HR);
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
                new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()),
                new CycleEmployeeSelectionRequest(otherEmployee.getId(), manager.getId()))));
        cycleService.publish(cycleId);

        assignmentId = assignmentFor(cycleId, employee.getId());
        otherAssignmentId = assignmentFor(cycleId, otherEmployee.getId());

        List<EmployeeKpi> ownKpis = employeeKpiRepository.findByAssignment_IdOrderByIdAsc(assignmentId);
        firstEmployeeKpiId = ownKpis.get(0).getId();
        secondEmployeeKpiId = ownKpis.get(1).getId();
        List<EmployeeKpi> otherKpis = employeeKpiRepository.findByAssignment_IdOrderByIdAsc(otherAssignmentId);
        otherEmployeeKpiId = otherKpis.get(0).getId();
        otherEmployeeKpiId2 = otherKpis.get(1).getId();

        Long draftCycleId = cycleService.create(CycleTestData.request("Draft 2026"), hrUser.getId()).id();
        cycleService.configureKpis(draftCycleId, new ConfigureCycleKpisRequest(List.of(
                new CycleKpiSelectionRequest(deliveryMaster.getId(), null))));
        List<CycleEmployeeResponse> roster = cycleService.configureEmployees(draftCycleId,
                new ConfigureCycleEmployeesRequest(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()))));
        draftAssignmentId = roster.get(0).id();

        // The eligible assignment: employee and manager both submitted.
        submitSelfAssessment(employee, assignmentId);
        submitManagerAssessment(manager, assignmentId);

        // The other one stops at manager review.
        submitSelfAssessment(otherEmployee, otherAssignmentId);
    }

    // ------------------------------------------------------------- persistence

    @Test
    void oneRowPerEmployeeKpiAfterRepeatedSaves() {
        for (int attempt = 0; attempt < 3; attempt++) {
            saveDraft(entry(firstEmployeeKpiId, attempt + 1, "Pass " + attempt),
                    entry(secondEmployeeKpiId, 5, "Pass " + attempt));
        }

        List<HrAssessment> rows = hrAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId);
        assertThat(rows).hasSize(2);

        Map<Long, HrAssessment> byKpi = rows.stream()
                .collect(Collectors.toMap(row -> row.getEmployeeKpi().getId(), Function.identity()));
        assertThat(byKpi.get(firstEmployeeKpiId).getRating()).isEqualTo((short) 3);
        assertThat(byKpi.get(firstEmployeeKpiId).getComment()).isEqualTo("Pass 2");
        assertThat(byKpi.get(secondEmployeeKpiId).getRating()).isEqualTo((short) 5);
    }

    @Test
    void everyRowRecordsTheEditingHrUser() {
        saveDraft(entry(firstEmployeeKpiId, 4, "First editor."));
        assertThat(hrAssessmentRepository.findByEmployeeKpi_Id(firstEmployeeKpiId).orElseThrow()
                .getHr().getId()).isEqualTo(hrUser.getId());

        // Another HR account can edit; the stored author follows the last editor.
        hrAssessmentService.saveDraft(otherHrUser.getId(), assignmentId,
                request(entry(firstEmployeeKpiId, 5, "Second editor.")));

        List<HrAssessment> rows = hrAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getHr().getId()).isEqualTo(otherHrUser.getId());
        assertThat(rows.get(0).getRating()).isEqualTo((short) 5);
    }

    @Test
    void draftDoesNotChangeAssignmentStatusOrTimestamps() {
        saveDraft(entry(firstEmployeeKpiId, 4, "Still a draft."));

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.HR_REVIEW_PENDING);
        assertThat(assignment.getHrFinalizedAt()).isNull();
        assertThat(assignment.getResultsPublishedAt()).isNull();
    }

    @Test
    void emptyDraftCreatesNoRows() {
        hrAssessmentService.saveDraft(hrUser.getId(), assignmentId,
                new SaveHrAssessmentDraftRequest(List.of()));

        assertThat(hrAssessmentRepository.count()).isZero();
    }

    @Test
    void noHrAssessmentRowsExistOutsideTheAssignment() {
        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));

        assertThat(hrAssessmentRepository.count()).isEqualTo(2);
        assertThat(hrAssessmentRepository.findByEmployeeKpi_Assignment_Id(otherAssignmentId)).isEmpty();
        assertThat(hrAssessmentRepository.findByEmployeeKpi_Id(otherEmployeeKpiId)).isEmpty();
    }

    // ---------------------------------------------------------------- access

    @Test
    void nonHrUsersCannotUseTheService() {
        for (User outsider : List.of(manager.getUser(), employee.getUser())) {
            assertThatThrownBy(() -> hrAssessmentService.listPending(outsider.getId()))
                    .isInstanceOf(HrApiException.class)
                    .hasMessage("HR profile not found.");
            assertThatThrownBy(() -> hrAssessmentService.get(outsider.getId(), assignmentId))
                    .isInstanceOf(HrApiException.class)
                    .hasMessage("HR profile not found.");
            assertThatThrownBy(() -> hrAssessmentService.saveDraft(outsider.getId(), assignmentId,
                    request(entry(firstEmployeeKpiId, 3, null))))
                    .isInstanceOf(HrApiException.class)
                    .hasMessage("HR profile not found.");
            assertThatThrownBy(() -> hrAssessmentService.submit(outsider.getId(), assignmentId))
                    .isInstanceOf(HrApiException.class)
                    .hasMessage("HR profile not found.");
        }

        assertThat(hrAssessmentRepository.count()).isZero();
    }

    @Test
    void draftCannotReferenceAnotherAssignmentsKpi() {
        assertThatThrownBy(() -> saveDraftThrowing(entry(otherEmployeeKpiId, 3, null)))
                .isInstanceOf(HrApiException.class)
                .hasMessage("The selected KPI does not belong to this assessment.");

        assertThat(hrAssessmentRepository.count()).isZero();
    }

    @Test
    void unknownEmployeeKpiIdIsRejected() {
        assertThatThrownBy(() -> saveDraftThrowing(entry(999_999L, 3, null)))
                .isInstanceOf(HrApiException.class)
                .hasMessage("The selected KPI does not belong to this assessment.");
    }

    @Test
    void draftCycleIsInvisibleToTheService() {
        assertThatThrownBy(() -> hrAssessmentService.get(hrUser.getId(), draftAssignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("HR assessment not found.");

        assertThatThrownBy(() -> hrAssessmentService.submit(hrUser.getId(), draftAssignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("HR assessment not found.");

        assertThat(hrAssessmentRepository.count()).isZero();
    }

    /** The visibility rule is exactly {@code PUBLISHED}: no other cycle status qualifies. */
    @Test
    void onlyPublishedCyclesAreVisibleToTheService() {
        assertThat(hrAssessmentService.listPending(hrUser.getId())).hasSize(1);

        PerformanceCycle cycle = cycleRepository.findById(cycleId).orElseThrow();
        cycle.setStatus(CycleStatus.IN_PROGRESS);
        cycleRepository.saveAndFlush(cycle);

        assertThat(hrAssessmentService.listPending(hrUser.getId())).isEmpty();
        assertThatThrownBy(() -> hrAssessmentService.get(hrUser.getId(), assignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("HR assessment not found.");
        assertThatThrownBy(() -> hrAssessmentService.getStatus(hrUser.getId(), assignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("HR assessment not found.");
        assertThatThrownBy(() -> saveDraftThrowing(entry(firstEmployeeKpiId, 4, null)))
                .isInstanceOf(HrApiException.class)
                .hasMessage("HR assessment not found.");

        assertThat(hrAssessmentRepository.count()).isZero();

        cycle.setStatus(CycleStatus.COMPLETED);
        cycleRepository.saveAndFlush(cycle);

        assertThat(hrAssessmentService.listPending(hrUser.getId())).isEmpty();
        assertThatThrownBy(() -> hrAssessmentService.submit(hrUser.getId(), assignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("HR assessment not found.");
        assertThat(assignmentRepository.findById(assignmentId).orElseThrow().getStatus())
                .isEqualTo(AssignmentStatus.HR_REVIEW_PENDING);
    }

    @Test
    void assignmentWithoutManagerSubmissionIsInvisible() {
        assertThatThrownBy(() -> hrAssessmentService.get(hrUser.getId(), otherAssignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("HR assessment not found.");

        assertThat(hrAssessmentService.listPending(hrUser.getId()))
                .extracting(item -> item.assignmentId())
                .doesNotContain(otherAssignmentId);
    }

    // ------------------------------------------------------------- submission

    @Test
    void submissionPersistsValuesFinalizationAndCompletion() {
        saveDraft(entry(firstEmployeeKpiId, 4, "Delivered."), entry(secondEmployeeKpiId, 5, "Solid."));
        java.time.OffsetDateTime managerSubmittedAt = assignmentRepository.findById(assignmentId)
                .orElseThrow().getManagerSubmittedAt();

        HrAssessmentStatusResponse submitted = hrAssessmentService.submit(hrUser.getId(), assignmentId);

        assertThat(submitted.submitted()).isTrue();
        assertThat(submitted.assignmentStatus()).isEqualTo("COMPLETED");
        assertThat(submitted.hrFinalizedAt()).isNotNull();
        assertThat(submitted.ratedKpis()).isEqualTo(2);
        assertThat(submitted.totalKpis()).isEqualTo(2);

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(assignment.getHrFinalizedAt()).isNotNull();
        assertThat(assignment.getResultsPublishedAt()).isNull();
        assertThat(assignment.getManagerSubmittedAt()).isEqualTo(managerSubmittedAt);
        assertThat(cycleRepository.findById(cycleId).orElseThrow().getStatus())
                .isEqualTo(CycleStatus.PUBLISHED);

        PerformanceResult result = resultRepository.findByAssignment_Id(assignmentId).orElseThrow();
        assertThat(result.getOverallScore()).isEqualByComparingTo("4.20");
        assertThat(result.getCalculationMode()).isEqualTo(CalculationMode.WEIGHTED);
        assertThat(result.getGrade()).isEqualTo(PerformanceGrade.EXCELLENT);
        assertThat(result.getStatus()).isEqualTo(ResultStatus.DRAFT);
        assertThat(result.getPublishedAt()).isNull();
        assertThat(resultItemRepository.findByResult_Id(result.getId())).hasSize(2);

        List<HrAssessment> rows = hrAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getHr().getId()).isEqualTo(hrUser.getId());
            assertThat(row.getRating()).isNotNull();
        });
    }

    @Test
    void submissionCreatesOneResultWithoutTouchingSelfOrManagerAssessments() {
        List<SelfAssessment> selfBefore =
                selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId);
        List<ManagerAssessment> managerBefore =
                managerAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId);
        assertThat(selfBefore).hasSize(2);
        assertThat(managerBefore).hasSize(2);

        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));
        hrAssessmentService.submit(hrUser.getId(), assignmentId);

        List<SelfAssessment> selfAfter =
                selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId);
        List<ManagerAssessment> managerAfter =
                managerAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId);
        assertThat(selfAfter).hasSize(2);
        assertThat(managerAfter).hasSize(2);
        Map<Long, ManagerAssessment> managerBeforeByKpi = managerBefore.stream()
                .collect(Collectors.toMap(row -> row.getEmployeeKpi().getId(), Function.identity()));
        assertThat(managerAfter).allSatisfy(row -> {
            ManagerAssessment original = managerBeforeByKpi.get(row.getEmployeeKpi().getId());
            assertThat(row.getRating()).isEqualTo(original.getRating());
            assertThat(row.getComment()).isEqualTo(original.getComment());
        });

        Map<Long, SelfAssessment> selfBeforeByKpi = selfBefore.stream()
                .collect(Collectors.toMap(row -> row.getEmployeeKpi().getId(), Function.identity()));
        assertThat(selfAfter).allSatisfy(row -> {
            SelfAssessment original = selfBeforeByKpi.get(row.getEmployeeKpi().getId());
            assertThat(row.getRating()).isEqualTo(original.getRating());
            assertThat(row.getComment()).isEqualTo(original.getComment());
        });

        assertThat(resultRepository.count()).isEqualTo(1);
        PerformanceResult result = resultRepository.findByAssignment_Id(assignmentId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(ResultStatus.DRAFT);
        assertThat(result.getPublishedAt()).isNull();
        assertThat(resultItemRepository.findByResult_Id(result.getId())).hasSize(2);
        assertThat(resultItemRepository.findByResult_Id(result.getId())).allSatisfy(item -> {
            assertThat(item.getSelfRating()).isNotNull();
            assertThat(item.getFinalScore()).isNotNull();
        });
    }

    @Test
    void submissionTwiceIsRejectedAndKeepsOneTimestamp() {
        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));
        hrAssessmentService.submit(hrUser.getId(), assignmentId);

        // Compare stored values: H2 truncates timestamps to microseconds.
        java.time.OffsetDateTime afterFirstSubmit = assignmentRepository
                .findById(assignmentId).orElseThrow().getHrFinalizedAt();
        assertThat(afterFirstSubmit).isNotNull();

        assertThatThrownBy(() -> hrAssessmentService.submit(hrUser.getId(), assignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("The HR assessment has already been submitted.");

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(assignment.getHrFinalizedAt()).isEqualTo(afterFirstSubmit);
        assertThat(resultRepository.count()).isEqualTo(1);
        PerformanceResult result = resultRepository.findByAssignment_Id(assignmentId).orElseThrow();
        assertThat(resultItemRepository.findByResult_Id(result.getId())).hasSize(2);
    }

    @Test
    void submittedAssessmentIsReadOnlyButStillReadable() {
        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));
        hrAssessmentService.submit(hrUser.getId(), assignmentId);

        assertThatThrownBy(() -> hrAssessmentService.saveDraft(hrUser.getId(), assignmentId,
                request(entry(firstEmployeeKpiId, 1, "Too late"))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("The HR assessment has already been submitted.");

        HrAssessmentResponse detail = hrAssessmentService.get(hrUser.getId(), assignmentId);
        assertThat(detail.editable()).isFalse();
        assertThat(detail.assignmentStatus()).isEqualTo("COMPLETED");
        assertThat(detail.hrFinalizedAt()).isNotNull();
        assertThat(ratingOf(detail, firstEmployeeKpiId)).isEqualTo(4);
        assertThat(hrAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId))
                .allSatisfy(row -> assertThat(row.getRating()).isNotEqualTo((short) 1));
    }

    // --------------------------------------------------------------- snapshots

    @Test
    void masterKpiChangesDoNotRewriteSnapshotsOrSavedDraft() {
        saveDraft(entry(firstEmployeeKpiId, 4, "HR words."), entry(secondEmployeeKpiId, 5, "HR words."));
        hrAssessmentService.submit(hrUser.getId(), assignmentId);

        deliveryMaster.setName("Delivery Excellence");
        deliveryMaster.setDescription("Rewritten description.");
        deliveryMaster.setCategory("Rewritten");
        deliveryMaster.setDefaultWeightage(new BigDecimal("99.00"));
        kpiRepository.saveAndFlush(deliveryMaster);

        HrAssessmentResponse detail = hrAssessmentService.get(hrUser.getId(), assignmentId);
        var snapshot = detail.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(firstEmployeeKpiId))
                .findFirst().orElseThrow();

        assertThat(snapshot.name()).isEqualTo("Delivery Quality");
        assertThat(snapshot.description()).isEqualTo("Meets agreed deadlines.");
        assertThat(snapshot.category()).isEqualTo("Delivery");
        assertThat(snapshot.weightage()).isEqualByComparingTo("60.00");
        assertThat(snapshot.hrRating()).isEqualTo(4);
        assertThat(snapshot.hrComment()).isEqualTo("HR words.");

        EmployeeKpi stored = employeeKpiRepository.findById(firstEmployeeKpiId).orElseThrow();
        assertThat(stored.getKpiName()).isEqualTo("Delivery Quality");

        PerformanceResult result = resultRepository.findByAssignment_Id(assignmentId).orElseThrow();
        PerformanceResultItem resultItem = resultItemRepository.findByResult_Id(result.getId()).stream()
                .filter(item -> item.getEmployeeKpi().getId().equals(firstEmployeeKpiId))
                .findFirst().orElseThrow();
        assertThat(resultItem.getKpiName()).isEqualTo("Delivery Quality");
        assertThat(resultItem.getWeightage()).isEqualByComparingTo("60.00");
        assertThat(resultItem.getManagerRating()).isEqualTo((short) 4);
        assertThat(resultItem.getHrRating()).isEqualTo((short) 4);
        assertThat(resultItem.getFinalScore()).isEqualByComparingTo("4.00");
    }

    // ----------------------------------------------------------------- helpers

    private void saveDraft(HrAssessmentEntryRequest... entries) {
        hrAssessmentService.saveDraft(hrUser.getId(), assignmentId, request(entries));
    }

    private Object saveDraftThrowing(HrAssessmentEntryRequest... entries) {
        return hrAssessmentService.saveDraft(hrUser.getId(), assignmentId, request(entries));
    }

    private static SaveHrAssessmentDraftRequest request(HrAssessmentEntryRequest... entries) {
        return new SaveHrAssessmentDraftRequest(List.of(entries));
    }

    private static HrAssessmentEntryRequest entry(Long employeeKpiId, Integer rating, String comment) {
        return new HrAssessmentEntryRequest(employeeKpiId,
                rating == null ? null : new BigDecimal(rating), comment);
    }

    private static Integer ratingOf(HrAssessmentResponse response, Long employeeKpiId) {
        return response.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(employeeKpiId))
                .findFirst().orElseThrow().hrRating();
    }

    private void submitSelfAssessment(Employee owner, Long assignment) {
        List<EmployeeKpi> kpis = employeeKpiRepository.findByAssignment_IdOrderByIdAsc(assignment);
        Long ownerUserId = owner.getUser().getId();
        selfAssessmentService.saveDraft(ownerUserId, assignment,
                new SaveSelfAssessmentDraftRequest(List.of(
                        new SelfAssessmentEntryRequest(kpis.get(0).getId(), new BigDecimal("4"),
                                "Delivered on time."),
                        new SelfAssessmentEntryRequest(kpis.get(1).getId(), new BigDecimal("5"),
                                "Solid quality."))));
        selfAssessmentService.submit(ownerUserId, assignment);
    }

    private void submitManagerAssessment(Employee reviewer, Long assignment) {
        List<ManagerAssessmentEntryRequest> ratings = employeeKpiRepository
                .findByAssignment_IdOrderByIdAsc(assignment).stream()
                .map(kpi -> new ManagerAssessmentEntryRequest(kpi.getId(), new BigDecimal("4"), "Manager review."))
                .toList();
        managerAssessmentService.saveDraft(reviewer.getUser().getId(), assignment,
                new SaveManagerAssessmentDraftRequest(ratings));
        managerAssessmentService.submit(reviewer.getUser().getId(), assignment);
    }

    private Long assignmentFor(Long cycleId, Long employeeId) {
        return assignmentRepository.findByCycle_IdOrderByEmployee_IdAsc(cycleId).stream()
                .filter(assignment -> assignment.getEmployee().getId().equals(employeeId))
                .findFirst()
                .orElseThrow()
                .getId();
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
