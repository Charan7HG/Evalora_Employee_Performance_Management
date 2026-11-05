package com.evalora.backend.service;

import com.evalora.backend.dto.ConfigureCycleEmployeesRequest;
import com.evalora.backend.dto.ConfigureCycleKpisRequest;
import com.evalora.backend.dto.CycleEmployeeResponse;
import com.evalora.backend.dto.CycleEmployeeSelectionRequest;
import com.evalora.backend.dto.CycleKpiSelectionRequest;
import com.evalora.backend.dto.SaveSelfAssessmentDraftRequest;
import com.evalora.backend.dto.SelfAssessmentEntryRequest;
import com.evalora.backend.dto.SelfAssessmentResponse;
import com.evalora.backend.dto.SelfAssessmentStatusResponse;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.EmployeeAssignment;
import com.evalora.backend.entity.EmployeeKpi;
import com.evalora.backend.entity.KpiMaster;
import com.evalora.backend.entity.PerformanceCycle;
import com.evalora.backend.entity.SelfAssessment;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.AssignmentStatus;
import com.evalora.backend.entity.enums.CycleStatus;
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
 * Phase 3E data integrity at the service boundary: one row per assigned KPI,
 * ownership, snapshot stability, and the separation from manager/HR scoring.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_self_assessment_data;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000"
})
class SelfAssessmentDataIntegrityIntegrationTest {

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
    private KpiMasterRepository kpiRepository;

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

    @BeforeEach
    void setUp() {
        clearDatabase();

        User hrUser = userRepository.saveAndFlush(
                CycleTestData.user("hr.self.data@evalora.local", Role.HR));
        Employee manager = profile(Role.MANAGER, "MGR-DATA", "Mona Manager");
        employee = profile(Role.EMPLOYEE, "EMP-DATA", "Eve Employee");
        otherEmployee = profile(Role.EMPLOYEE, "EMP-DATA-2", "Oscar Other");

        deliveryMaster = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Delivery Quality", "Meets agreed deadlines.", "Delivery"));
        qualityMaster = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Quality", "Work meets the quality bar.", "Quality"));

        Long cycleId = cycleService.create(CycleTestData.request("Annual 2026"), hrUser.getId()).id();
        this.cycleId = cycleId;
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
        otherEmployeeKpiId = employeeKpiRepository.findByAssignment_IdOrderByIdAsc(otherAssignmentId).get(0).getId();

        Long draftCycleId = cycleService.create(CycleTestData.request("Draft 2026"), hrUser.getId()).id();
        cycleService.configureKpis(draftCycleId, new ConfigureCycleKpisRequest(List.of(
                new CycleKpiSelectionRequest(deliveryMaster.getId(), null))));
        List<CycleEmployeeResponse> roster = cycleService.configureEmployees(draftCycleId,
                new ConfigureCycleEmployeesRequest(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()))));
        draftAssignmentId = roster.get(0).id();
    }

    // ------------------------------------------------------------- persistence

    @Test
    void oneRowPerEmployeeKpiAfterRepeatedSaves() {
        for (int attempt = 0; attempt < 3; attempt++) {
            saveDraft(entry(firstEmployeeKpiId, attempt + 1, "Pass " + attempt),
                    entry(secondEmployeeKpiId, 5, "Pass " + attempt));
        }

        List<SelfAssessment> rows = selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId);
        assertThat(rows).hasSize(2);

        Map<Long, SelfAssessment> byKpi = rows.stream()
                .collect(Collectors.toMap(row -> row.getEmployeeKpi().getId(), Function.identity()));
        assertThat(byKpi.get(firstEmployeeKpiId).getRating()).isEqualTo((short) 3);
        assertThat(byKpi.get(firstEmployeeKpiId).getComment()).isEqualTo("Pass 2");
        assertThat(byKpi.get(secondEmployeeKpiId).getRating()).isEqualTo((short) 5);
    }

    @Test
    void draftDoesNotChangeAssignmentStatus() {
        saveDraft(entry(firstEmployeeKpiId, 4, "Still a draft."));

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.SELF_ASSESSMENT_PENDING);
        assertThat(assignment.getSelfSubmittedAt()).isNull();
    }

    @Test
    void emptyDraftCreatesNoRows() {
        selfAssessmentService.saveDraft(employee.getUser().getId(), assignmentId,
                new SaveSelfAssessmentDraftRequest(List.of()));

        assertThat(selfAssessmentRepository.count()).isZero();
    }

    @Test
    void noSelfAssessmentRowsExistOutsideTheAssignment() {
        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));

        assertThat(selfAssessmentRepository.count()).isEqualTo(2);
        assertThat(selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(otherAssignmentId)).isEmpty();
        assertThat(selfAssessmentRepository.findByEmployeeKpi_Id(otherEmployeeKpiId)).isEmpty();
    }

    // ---------------------------------------------------------------- ownership

    @Test
    void draftCannotWriteToAnotherEmployeesAssignment() {
        assertThatThrownBy(() -> selfAssessmentService.saveDraft(otherEmployee.getUser().getId(),
                assignmentId,
                request(entry(firstEmployeeKpiId, 3, null))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Self-assessment not found.");

        assertThat(selfAssessmentRepository.count()).isZero();
    }

    @Test
    void draftCannotReferenceAnotherEmployeesKpi() {
        assertThatThrownBy(() -> selfAssessmentService.saveDraft(employee.getUser().getId(),
                assignmentId,
                request(entry(otherEmployeeKpiId, 3, null))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("The selected KPI does not belong to this self-assessment.");

        assertThat(selfAssessmentRepository.count()).isZero();
    }

    @Test
    void unknownEmployeeKpiIdIsRejected() {
        assertThatThrownBy(() -> selfAssessmentService.saveDraft(employee.getUser().getId(),
                assignmentId,
                request(entry(999_999L, 3, null))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("The selected KPI does not belong to this self-assessment.");
    }

    @Test
    void draftCycleIsInvisibleToTheService() {
        assertThatThrownBy(() -> selfAssessmentService.get(employee.getUser().getId(), draftAssignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Self-assessment not found.");

        assertThatThrownBy(() -> selfAssessmentService.submit(employee.getUser().getId(), draftAssignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Self-assessment not found.");

        assertThat(selfAssessmentRepository.count()).isZero();
    }

    /** Visibility is exactly {@code PUBLISHED}; no other cycle status qualifies. */
    @Test
    void onlyPublishedCyclesAreVisibleToTheService() {
        Long userId = employee.getUser().getId();
        assertThat(selfAssessmentService.listCycles(userId)).hasSize(1);

        PerformanceCycle cycle = cycleRepository.findById(cycleId).orElseThrow();
        cycle.setStatus(CycleStatus.IN_PROGRESS);
        cycleRepository.saveAndFlush(cycle);

        assertThat(selfAssessmentService.listCycles(userId)).isEmpty();
        assertThatThrownBy(() -> selfAssessmentService.get(userId, assignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Self-assessment not found.");
        assertThatThrownBy(() -> selfAssessmentService.getStatus(userId, assignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Self-assessment not found.");
        assertThatThrownBy(() -> selfAssessmentService.saveDraft(userId, assignmentId,
                request(entry(firstEmployeeKpiId, 4, null))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Self-assessment not found.");

        assertThat(selfAssessmentRepository.count()).isZero();

        cycle.setStatus(CycleStatus.COMPLETED);
        cycleRepository.saveAndFlush(cycle);

        assertThat(selfAssessmentService.listCycles(userId)).isEmpty();
        assertThatThrownBy(() -> selfAssessmentService.submit(userId, assignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Self-assessment not found.");
        assertThat(assignmentRepository.findById(assignmentId).orElseThrow().getStatus())
                .isEqualTo(AssignmentStatus.SELF_ASSESSMENT_PENDING);
    }

    @Test
    void submissionRequiresOwnership() {
        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));

        assertThatThrownBy(() -> selfAssessmentService.submit(otherEmployee.getUser().getId(), assignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Self-assessment not found.");

        assertThat(assignmentRepository.findById(assignmentId).orElseThrow().getStatus())
                .isEqualTo(AssignmentStatus.SELF_ASSESSMENT_PENDING);
    }

    // --------------------------------------------------------------- submission

    @Test
    void submissionPersistsTheSavedValuesAndTheTimestamp() {
        saveDraft(entry(firstEmployeeKpiId, 4, "Delivered."), entry(secondEmployeeKpiId, 5, "Solid."));

        SelfAssessmentStatusResponse submitted = selfAssessmentService.submit(
                employee.getUser().getId(), assignmentId);

        assertThat(submitted.submitted()).isTrue();
        assertThat(submitted.assignmentStatus()).isEqualTo("MANAGER_REVIEW_PENDING");
        assertThat(submitted.selfSubmittedAt()).isNotNull();
        assertThat(submitted.ratedKpis()).isEqualTo(2);

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.MANAGER_REVIEW_PENDING);
        assertThat(assignment.getSelfSubmittedAt()).isNotNull();

        List<SelfAssessment> rows = selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row.getRating()).isNotNull());
    }

    @Test
    void submissionDoesNotTouchManagerOrHrAssessmentsOrResults() {
        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));
        selfAssessmentService.submit(employee.getUser().getId(), assignmentId);

        assertThat(managerAssessmentRepository.count()).isZero();
        assertThat(hrAssessmentRepository.count()).isZero();
        assertThat(resultRepository.count()).isZero();
    }

    @Test
    void submissionTwiceIsRejectedAndKeepsOneTimestamp() {
        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));
        selfAssessmentService.submit(employee.getUser().getId(), assignmentId);

        // Compare the stored values: H2 truncates timestamps to microseconds,
        // so the in-memory value must not be used for an exact comparison.
        java.time.OffsetDateTime afterFirstSubmit = assignmentRepository
                .findById(assignmentId).orElseThrow().getSelfSubmittedAt();
        assertThat(afterFirstSubmit).isNotNull();

        assertThatThrownBy(() -> selfAssessmentService.submit(employee.getUser().getId(), assignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("The self-assessment has already been submitted.");

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.MANAGER_REVIEW_PENDING);
        assertThat(assignment.getSelfSubmittedAt()).isEqualTo(afterFirstSubmit);
    }

    @Test
    void submittedAssessmentIsReadOnly() {
        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));
        selfAssessmentService.submit(employee.getUser().getId(), assignmentId);

        assertThatThrownBy(() -> selfAssessmentService.saveDraft(employee.getUser().getId(),
                assignmentId,
                request(entry(firstEmployeeKpiId, 1, "Too late"))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("The self-assessment has already been submitted.");

        SelfAssessmentResponse detail = selfAssessmentService.get(employee.getUser().getId(), assignmentId);
        assertThat(detail.editable()).isFalse();
        assertThat(detail.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(firstEmployeeKpiId))
                .findFirst().orElseThrow().rating()).isEqualTo(4);
    }

    // --------------------------------------------------------------- snapshots

    @Test
    void masterKpiChangesDoNotRewriteSnapshotsOrSavedDraft() {
        saveDraft(entry(firstEmployeeKpiId, 4, "My own words."),
                entry(secondEmployeeKpiId, 5, "Also mine."));
        selfAssessmentService.submit(employee.getUser().getId(), assignmentId);

        deliveryMaster.setName("Delivery Excellence");
        deliveryMaster.setDescription("Rewritten description.");
        deliveryMaster.setCategory("Rewritten");
        deliveryMaster.setDefaultWeightage(new BigDecimal("99.00"));
        kpiRepository.saveAndFlush(deliveryMaster);

        SelfAssessmentResponse detail = selfAssessmentService.get(employee.getUser().getId(), assignmentId);
        var snapshot = detail.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(firstEmployeeKpiId))
                .findFirst().orElseThrow();

        assertThat(snapshot.name()).isEqualTo("Delivery Quality");
        assertThat(snapshot.description()).isEqualTo("Meets agreed deadlines.");
        assertThat(snapshot.category()).isEqualTo("Delivery");
        assertThat(snapshot.weightage()).isEqualByComparingTo("60.00");
        assertThat(snapshot.rating()).isEqualTo(4);
        assertThat(snapshot.comment()).isEqualTo("My own words.");

        EmployeeKpi stored = employeeKpiRepository.findById(firstEmployeeKpiId).orElseThrow();
        assertThat(stored.getKpiName()).isEqualTo("Delivery Quality");
    }

    // ----------------------------------------------------------------- helpers

    private void saveDraft(SelfAssessmentEntryRequest... entries) {
        selfAssessmentService.saveDraft(employee.getUser().getId(), assignmentId, request(entries));
    }

    private static SaveSelfAssessmentDraftRequest request(SelfAssessmentEntryRequest... entries) {
        return new SaveSelfAssessmentDraftRequest(List.of(entries));
    }

    private static SelfAssessmentEntryRequest entry(Long employeeKpiId, Integer rating, String comment) {
        return new SelfAssessmentEntryRequest(employeeKpiId,
                rating == null ? null : new BigDecimal(rating), comment);
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
