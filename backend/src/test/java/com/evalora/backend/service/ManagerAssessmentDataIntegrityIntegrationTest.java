package com.evalora.backend.service;

import com.evalora.backend.dto.ConfigureCycleEmployeesRequest;
import com.evalora.backend.dto.ConfigureCycleKpisRequest;
import com.evalora.backend.dto.CycleEmployeeResponse;
import com.evalora.backend.dto.CycleEmployeeSelectionRequest;
import com.evalora.backend.dto.CycleKpiSelectionRequest;
import com.evalora.backend.dto.ManagerAssessmentEntryRequest;
import com.evalora.backend.dto.ManagerAssessmentResponse;
import com.evalora.backend.dto.ManagerAssessmentStatusResponse;
import com.evalora.backend.dto.SaveManagerAssessmentDraftRequest;
import com.evalora.backend.dto.SaveSelfAssessmentDraftRequest;
import com.evalora.backend.dto.SelfAssessmentEntryRequest;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.EmployeeAssignment;
import com.evalora.backend.entity.EmployeeKpi;
import com.evalora.backend.entity.KpiMaster;
import com.evalora.backend.entity.ManagerAssessment;
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
 * Phase 3F data integrity at the service boundary.
 *
 * <p>Proves the properties the HTTP suite cannot see: exactly one row per
 * assigned KPI, the assignment's manager recorded on every row, ownership that
 * survives a later reporting-line change, master-KPI snapshot stability, and
 * complete separation from the employee self-assessment, HR assessment and
 * performance-result tables.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_manager_assessment_data;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000"
})
class ManagerAssessmentDataIntegrityIntegrationTest {

    @Autowired
    private ManagerAssessmentService managerAssessmentService;

    @Autowired
    private SelfAssessmentService selfAssessmentService;

    @Autowired
    private PerformanceCycleService cycleService;

    @Autowired
    private EmployeeManagementService employeeManagementService;

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

    private User hrUser;
    private Employee manager;
    private Employee otherManager;
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

        hrUser = userRepository.saveAndFlush(
                CycleTestData.user("hr.manager.data@evalora.local", Role.HR));
        manager = profile(Role.MANAGER, "MGR-MAN-DATA", "Mona Manager");
        otherManager = profile(Role.MANAGER, "MGR-MAN-OTHER", "Nate Other");
        employee = profile(Role.EMPLOYEE, "EMP-MAN-DATA", "Eve Employee");
        otherEmployee = profile(Role.EMPLOYEE, "EMP-MAN-DATA-2", "Oscar Other");

        deliveryMaster = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Delivery Quality", "Meets agreed deadlines.", "Delivery"));
        qualityMaster = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Quality", "Work meets the quality bar.", "Quality"));

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
        otherEmployeeKpiId = employeeKpiRepository.findByAssignment_IdOrderByIdAsc(otherAssignmentId)
                .get(0).getId();

        Long draftCycleId = cycleService.create(CycleTestData.request("Draft 2026"), hrUser.getId()).id();
        cycleService.configureKpis(draftCycleId, new ConfigureCycleKpisRequest(List.of(
                new CycleKpiSelectionRequest(deliveryMaster.getId(), null))));
        List<CycleEmployeeResponse> roster = cycleService.configureEmployees(draftCycleId,
                new ConfigureCycleEmployeesRequest(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()))));
        draftAssignmentId = roster.get(0).id();

        // The manager can only see an assignment whose employee has submitted.
        submitSelfAssessment();
    }

    // ------------------------------------------------------------- persistence

    @Test
    void oneRowPerEmployeeKpiAfterRepeatedSaves() {
        for (int attempt = 0; attempt < 3; attempt++) {
            saveDraft(entry(firstEmployeeKpiId, attempt + 1, "Pass " + attempt),
                    entry(secondEmployeeKpiId, 5, "Pass " + attempt));
        }

        List<ManagerAssessment> rows =
                managerAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId);
        assertThat(rows).hasSize(2);

        Map<Long, ManagerAssessment> byKpi = rows.stream()
                .collect(Collectors.toMap(row -> row.getEmployeeKpi().getId(), Function.identity()));
        assertThat(byKpi.get(firstEmployeeKpiId).getRating()).isEqualTo((short) 3);
        assertThat(byKpi.get(firstEmployeeKpiId).getComment()).isEqualTo("Pass 2");
        assertThat(byKpi.get(secondEmployeeKpiId).getRating()).isEqualTo((short) 5);
    }

    @Test
    void everyRowRecordsTheAssignmentManager() {
        saveDraft(entry(firstEmployeeKpiId, 4, "Mine."), entry(secondEmployeeKpiId, 5, "Mine."));

        List<ManagerAssessment> rows =
                managerAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row ->
                assertThat(row.getManager().getId()).isEqualTo(manager.getId()));
    }

    @Test
    void draftDoesNotChangeAssignmentStatusOrTimestamps() {
        saveDraft(entry(firstEmployeeKpiId, 4, "Still a draft."));

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.MANAGER_REVIEW_PENDING);
        assertThat(assignment.getManagerSubmittedAt()).isNull();
        assertThat(assignment.getHrFinalizedAt()).isNull();
    }

    @Test
    void emptyDraftCreatesNoRows() {
        managerAssessmentService.saveDraft(manager.getUser().getId(), assignmentId,
                new SaveManagerAssessmentDraftRequest(List.of()));

        assertThat(managerAssessmentRepository.count()).isZero();
    }

    @Test
    void noManagerAssessmentRowsExistOutsideTheAssignment() {
        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));

        assertThat(managerAssessmentRepository.count()).isEqualTo(2);
        assertThat(managerAssessmentRepository.findByEmployeeKpi_Assignment_Id(otherAssignmentId))
                .isEmpty();
        assertThat(managerAssessmentRepository.findByEmployeeKpi_Id(otherEmployeeKpiId)).isEmpty();
    }

    // ---------------------------------------------------------------- ownership

    @Test
    void managerRoleIsRequiredByTheService() {
        assertThatThrownBy(() -> managerAssessmentService.listPending(employee.getUser().getId()))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Manager profile not found.");
    }

    @Test
    void draftCannotWriteToAnotherManagersAssignment() {
        assertThatThrownBy(() -> managerAssessmentService.saveDraft(otherManager.getUser().getId(),
                assignmentId, request(entry(firstEmployeeKpiId, 3, null))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Manager assessment not found.");

        assertThat(managerAssessmentRepository.count()).isZero();
    }

    @Test
    void draftCannotReferenceAnotherAssignmentsKpi() {
        assertThatThrownBy(() -> managerAssessmentService.saveDraft(manager.getUser().getId(),
                assignmentId, request(entry(otherEmployeeKpiId, 3, null))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("The selected KPI does not belong to this assessment.");

        assertThat(managerAssessmentRepository.count()).isZero();
    }

    @Test
    void unknownEmployeeKpiIdIsRejected() {
        assertThatThrownBy(() -> managerAssessmentService.saveDraft(manager.getUser().getId(),
                assignmentId, request(entry(999_999L, 3, null))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("The selected KPI does not belong to this assessment.");
    }

    @Test
    void draftCycleIsInvisibleToTheService() {
        assertThatThrownBy(() -> managerAssessmentService.get(manager.getUser().getId(), draftAssignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Manager assessment not found.");

        assertThatThrownBy(() -> managerAssessmentService.submit(manager.getUser().getId(), draftAssignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Manager assessment not found.");

        assertThat(managerAssessmentRepository.count()).isZero();
    }

    /**
     * The visibility rule is exactly {@code PUBLISHED}: moving the cycle on
     * hides the review even from the assigned manager.
     */
    @Test
    void onlyPublishedCyclesAreVisibleToTheService() {
        Long managerUserId = manager.getUser().getId();
        assertThat(managerAssessmentService.listPending(managerUserId)).hasSize(1);

        PerformanceCycle cycle = cycleRepository.findById(cycleId).orElseThrow();
        cycle.setStatus(CycleStatus.IN_PROGRESS);
        cycleRepository.saveAndFlush(cycle);

        assertThat(managerAssessmentService.listPending(managerUserId)).isEmpty();
        assertThatThrownBy(() -> managerAssessmentService.get(managerUserId, assignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Manager assessment not found.");
        assertThatThrownBy(() -> managerAssessmentService.getStatus(managerUserId, assignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Manager assessment not found.");
        assertThatThrownBy(() -> managerAssessmentService.saveDraft(managerUserId, assignmentId,
                request(entry(firstEmployeeKpiId, 4, null))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Manager assessment not found.");

        assertThat(managerAssessmentRepository.count()).isZero();

        cycle.setStatus(CycleStatus.COMPLETED);
        cycleRepository.saveAndFlush(cycle);

        assertThat(managerAssessmentService.listPending(managerUserId)).isEmpty();
        assertThatThrownBy(() -> managerAssessmentService.submit(managerUserId, assignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Manager assessment not found.");
        assertThat(assignmentRepository.findById(assignmentId).orElseThrow().getStatus())
                .isEqualTo(AssignmentStatus.MANAGER_REVIEW_PENDING);
    }

    /** An assignment still waiting for the employee's self-assessment is hidden. */
    @Test
    void assignmentWithoutSubmittedSelfAssessmentIsInvisible() {
        assertThatThrownBy(() -> managerAssessmentService.get(manager.getUser().getId(), otherAssignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Manager assessment not found.");

        assertThat(managerAssessmentService.listPending(manager.getUser().getId()))
                .extracting(item -> item.assignmentId())
                .doesNotContain(otherAssignmentId);
    }

    @Test
    void submissionRequiresOwnership() {
        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));

        assertThatThrownBy(() -> managerAssessmentService.submit(otherManager.getUser().getId(), assignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Manager assessment not found.");

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.MANAGER_REVIEW_PENDING);
        assertThat(assignment.getManagerSubmittedAt()).isNull();
    }

    /**
     * The audit's core scenario: an employee is reassigned to a new reporting
     * manager after the cycle was published. The appraisal stays with the
     * manager recorded on the assignment at configuration time; the new
     * reporting manager cannot read, write or submit it.
     */
    @Test
    void reassigningTheEmployeesCurrentManagerDoesNotTransferTheAppraisal() {
        saveDraft(entry(firstEmployeeKpiId, 4, "Original manager."));

        employeeManagementService.assignManager(employee.getId(), otherManager.getId(), hrUser.getId());

        Employee reloaded = employeeRepository.findById(employee.getId()).orElseThrow();
        assertThat(reloaded.getManager().getId()).isEqualTo(otherManager.getId());
        assertThat(assignmentRepository.findById(assignmentId).orElseThrow().getManager().getId())
                .isEqualTo(manager.getId());

        // The original manager keeps the appraisal.
        assertThat(managerAssessmentService.listPending(manager.getUser().getId()))
                .extracting(item -> item.assignmentId())
                .contains(assignmentId);
        ManagerAssessmentResponse detail =
                managerAssessmentService.get(manager.getUser().getId(), assignmentId);
        assertThat(ratingOf(detail, firstEmployeeKpiId)).isEqualTo(4);

        // The new reporting manager gets nothing and sees nothing.
        assertThat(managerAssessmentService.listPending(otherManager.getUser().getId())).isEmpty();
        assertThatThrownBy(() -> managerAssessmentService.get(otherManager.getUser().getId(), assignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Manager assessment not found.");
        assertThatThrownBy(() -> managerAssessmentService.saveDraft(otherManager.getUser().getId(),
                assignmentId, request(entry(firstEmployeeKpiId, 5, "Hijack."))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Manager assessment not found.");
        assertThatThrownBy(() -> managerAssessmentService.submit(otherManager.getUser().getId(), assignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("Manager assessment not found.");

        // And the original manager can still finish the review.
        saveDraft(entry(secondEmployeeKpiId, 5, "Original manager."));
        ManagerAssessmentStatusResponse submitted =
                managerAssessmentService.submit(manager.getUser().getId(), assignmentId);
        assertThat(submitted.submitted()).isTrue();
        assertThat(submitted.assignmentStatus()).isEqualTo("HR_REVIEW_PENDING");
    }

    // --------------------------------------------------------------- submission

    @Test
    void submissionPersistsTheSavedValuesTimestampAndStatus() {
        saveDraft(entry(firstEmployeeKpiId, 4, "Delivered."), entry(secondEmployeeKpiId, 5, "Solid."));
        Long managerId = manager.getId();
        Long cycleOfAssignment = cycleIdOf(assignmentId);
        java.time.OffsetDateTime selfSubmittedAt = assignmentRepository.findById(assignmentId)
                .orElseThrow().getSelfSubmittedAt();

        ManagerAssessmentStatusResponse submitted =
                managerAssessmentService.submit(manager.getUser().getId(), assignmentId);

        assertThat(submitted.submitted()).isTrue();
        assertThat(submitted.assignmentStatus()).isEqualTo("HR_REVIEW_PENDING");
        assertThat(submitted.managerSubmittedAt()).isNotNull();
        assertThat(submitted.ratedKpis()).isEqualTo(2);
        assertThat(submitted.totalKpis()).isEqualTo(2);

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.HR_REVIEW_PENDING);
        assertThat(assignment.getManagerSubmittedAt()).isNotNull();
        assertThat(assignment.getHrFinalizedAt()).isNull();
        assertThat(assignment.getSelfSubmittedAt()).isEqualTo(selfSubmittedAt);
        assertThat(assignment.getManager().getId()).isEqualTo(managerId);

        // Only the assignment moves; the cycle stays published.
        assertThat(cycleRepository.findById(cycleOfAssignment).orElseThrow().getStatus())
                .isEqualTo(CycleStatus.PUBLISHED);

        List<ManagerAssessment> rows =
                managerAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getManager().getId()).isEqualTo(managerId);
            assertThat(row.getRating()).isNotNull();
        });
    }

    @Test
    void submissionDoesNotTouchSelfAssessmentsHrAssessmentsOrResults() {
        List<SelfAssessment> before = selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId);
        assertThat(before).hasSize(2);

        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));
        managerAssessmentService.submit(manager.getUser().getId(), assignmentId);

        List<SelfAssessment> after = selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId);
        assertThat(after).hasSize(2);
        Map<Long, SelfAssessment> beforeByKpi = before.stream()
                .collect(Collectors.toMap(row -> row.getEmployeeKpi().getId(), Function.identity()));
        assertThat(after).allSatisfy(row -> {
            SelfAssessment original = beforeByKpi.get(row.getEmployeeKpi().getId());
            assertThat(row.getRating()).isEqualTo(original.getRating());
            assertThat(row.getComment()).isEqualTo(original.getComment());
        });

        assertThat(hrAssessmentRepository.count()).isZero();
        assertThat(resultRepository.count()).isZero();
    }

    @Test
    void submissionTwiceIsRejectedAndKeepsOneTimestamp() {
        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));
        managerAssessmentService.submit(manager.getUser().getId(), assignmentId);

        // Compare stored values: H2 truncates timestamps to microseconds.
        java.time.OffsetDateTime afterFirstSubmit = assignmentRepository
                .findById(assignmentId).orElseThrow().getManagerSubmittedAt();
        assertThat(afterFirstSubmit).isNotNull();

        assertThatThrownBy(() -> managerAssessmentService.submit(manager.getUser().getId(), assignmentId))
                .isInstanceOf(HrApiException.class)
                .hasMessage("The manager assessment has already been submitted.");

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.HR_REVIEW_PENDING);
        assertThat(assignment.getManagerSubmittedAt()).isEqualTo(afterFirstSubmit);
    }

    @Test
    void submittedAssessmentIsReadOnly() {
        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));
        managerAssessmentService.submit(manager.getUser().getId(), assignmentId);

        assertThatThrownBy(() -> managerAssessmentService.saveDraft(manager.getUser().getId(),
                assignmentId, request(entry(firstEmployeeKpiId, 1, "Too late"))))
                .isInstanceOf(HrApiException.class)
                .hasMessage("The manager assessment has already been submitted.");

        ManagerAssessmentResponse detail =
                managerAssessmentService.get(manager.getUser().getId(), assignmentId);
        assertThat(detail.editable()).isFalse();
        assertThat(detail.assignmentStatus()).isEqualTo("HR_REVIEW_PENDING");
        assertThat(ratingOf(detail, firstEmployeeKpiId)).isEqualTo(4);
        assertThat(managerAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId))
                .allSatisfy(row -> assertThat(row.getRating()).isNotEqualTo((short) 1));
    }

    // --------------------------------------------------------------- snapshots

    @Test
    void masterKpiChangesDoNotRewriteSnapshotsOrSavedDraft() {
        saveDraft(entry(firstEmployeeKpiId, 4, "My own words."),
                entry(secondEmployeeKpiId, 5, "Also mine."));
        managerAssessmentService.submit(manager.getUser().getId(), assignmentId);

        deliveryMaster.setName("Delivery Excellence");
        deliveryMaster.setDescription("Rewritten description.");
        deliveryMaster.setCategory("Rewritten");
        deliveryMaster.setDefaultWeightage(new BigDecimal("99.00"));
        kpiRepository.saveAndFlush(deliveryMaster);

        ManagerAssessmentResponse detail =
                managerAssessmentService.get(manager.getUser().getId(), assignmentId);
        var snapshot = detail.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(firstEmployeeKpiId))
                .findFirst().orElseThrow();

        assertThat(snapshot.name()).isEqualTo("Delivery Quality");
        assertThat(snapshot.description()).isEqualTo("Meets agreed deadlines.");
        assertThat(snapshot.category()).isEqualTo("Delivery");
        assertThat(snapshot.weightage()).isEqualByComparingTo("60.00");
        assertThat(snapshot.managerRating()).isEqualTo(4);
        assertThat(snapshot.managerComment()).isEqualTo("My own words.");

        EmployeeKpi stored = employeeKpiRepository.findById(firstEmployeeKpiId).orElseThrow();
        assertThat(stored.getKpiName()).isEqualTo("Delivery Quality");
    }

    // ----------------------------------------------------------------- helpers

    private void saveDraft(ManagerAssessmentEntryRequest... entries) {
        managerAssessmentService.saveDraft(manager.getUser().getId(), assignmentId, request(entries));
    }

    private static SaveManagerAssessmentDraftRequest request(ManagerAssessmentEntryRequest... entries) {
        return new SaveManagerAssessmentDraftRequest(List.of(entries));
    }

    private static ManagerAssessmentEntryRequest entry(Long employeeKpiId, Integer rating, String comment) {
        return new ManagerAssessmentEntryRequest(employeeKpiId,
                rating == null ? null : new BigDecimal(rating), comment);
    }

    private static Integer ratingOf(ManagerAssessmentResponse response, Long employeeKpiId) {
        return response.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(employeeKpiId))
                .findFirst().orElseThrow().managerRating();
    }

    private Long cycleIdOf(Long assignment) {
        return assignmentRepository.findById(assignment).orElseThrow().getCycle().getId();
    }

    private void submitSelfAssessment() {
        List<EmployeeKpi> kpis = employeeKpiRepository.findByAssignment_IdOrderByIdAsc(assignmentId);
        selfAssessmentService.saveDraft(employee.getUser().getId(), assignmentId,
                new SaveSelfAssessmentDraftRequest(List.of(
                        new SelfAssessmentEntryRequest(kpis.get(0).getId(), new BigDecimal("4"),
                                "Delivered on time."),
                        new SelfAssessmentEntryRequest(kpis.get(1).getId(), new BigDecimal("5"),
                                "Solid quality."))));
        selfAssessmentService.submit(employee.getUser().getId(), assignmentId);
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
