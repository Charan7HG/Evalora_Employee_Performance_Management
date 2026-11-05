package com.evalora.backend.controller;

import com.evalora.backend.dto.ConfigureCycleEmployeesRequest;
import com.evalora.backend.dto.ConfigureCycleKpisRequest;
import com.evalora.backend.dto.CycleEmployeeResponse;
import com.evalora.backend.dto.CycleEmployeeSelectionRequest;
import com.evalora.backend.dto.CycleKpiSelectionRequest;
import com.evalora.backend.dto.HrAssessmentKpiResponse;
import com.evalora.backend.dto.HrAssessmentResponse;
import com.evalora.backend.dto.HrAssessmentStatusResponse;
import com.evalora.backend.dto.HrQueueItemResponse;
import com.evalora.backend.dto.ManagerAssessmentEntryRequest;
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
import com.evalora.backend.service.ManagerAssessmentService;
import com.evalora.backend.service.PerformanceCycleService;
import com.evalora.backend.service.SelfAssessmentService;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HR assessment through the real filter chain, controller, service
 * and H2 in PostgreSQL mode.
 *
 * <p>H2 proves the JPA behaviour and the HTTP contract. It does not prove
 * PostgreSQL-specific index or row-lock behaviour.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_hr_assessment;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000"
})
class HrAssessmentIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

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

    @Autowired
    private PerformanceCycleService cycleService;

    @Autowired
    private SelfAssessmentService selfAssessmentService;

    @Autowired
    private ManagerAssessmentService managerAssessmentService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private User hrUser;
    private Employee manager;
    private Employee otherManager;
    private Employee employee;
    private Employee otherEmployee;
    private Employee pendingEmployee;
    private KpiMaster deliveryMaster;
    private KpiMaster qualityMaster;

    private String hrToken;
    private String managerToken;
    private String employeeToken;

    private Long cycleId;
    private Long assignmentId;
    private Long otherAssignmentId;
    private Long pendingAssignmentId;
    private Long draftAssignmentId;
    private Long firstEmployeeKpiId;
    private Long secondEmployeeKpiId;
    private Long otherEmployeeKpiId;

    @BeforeEach
    void setUp() {
        clearDatabase();

        hrUser = userRepository.saveAndFlush(CycleTestData.user("hr.assessor@evalora.local", Role.HR));
        manager = profile(Role.MANAGER, "MGR-HR-REV", "Mona Manager");
        otherManager = profile(Role.MANAGER, "MGR-HR-OTHER", "Nate Other");
        employee = profile(Role.EMPLOYEE, "EMP-HR-REV", "Eve Employee");
        otherEmployee = profile(Role.EMPLOYEE, "EMP-HR-REV-2", "Oscar Other");
        pendingEmployee = profile(Role.EMPLOYEE, "EMP-HR-REV-3", "Dana Delay");

        deliveryMaster = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Delivery Quality", "Meets agreed deadlines.", "Delivery"));
        deliveryMaster.setKpiType(KpiType.HR);
        deliveryMaster = kpiRepository.saveAndFlush(deliveryMaster);
        qualityMaster = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Quality", "Work meets the quality bar.", "Quality"));
        qualityMaster.setKpiType(KpiType.HR);
        qualityMaster = kpiRepository.saveAndFlush(qualityMaster);

        hrToken = jwtService.generateToken(hrUser);
        managerToken = jwtService.generateToken(manager.getUser());
        employeeToken = jwtService.generateToken(employee.getUser());

        cycleId = cycleService.create(CycleTestData.request("Annual 2026"), hrUser.getId()).id();
        cycleService.configureKpis(cycleId, new ConfigureCycleKpisRequest(List.of(
                new CycleKpiSelectionRequest(deliveryMaster.getId(), new BigDecimal("60.00")),
                new CycleKpiSelectionRequest(qualityMaster.getId(), new BigDecimal("40.00")))));
        cycleService.configureEmployees(cycleId, new ConfigureCycleEmployeesRequest(List.of(
                new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()),
                new CycleEmployeeSelectionRequest(otherEmployee.getId(), otherManager.getId()),
                new CycleEmployeeSelectionRequest(pendingEmployee.getId(), manager.getId()))));
        cycleService.publish(cycleId);

        assignmentId = assignmentFor(cycleId, employee.getId()).getId();
        otherAssignmentId = assignmentFor(cycleId, otherEmployee.getId()).getId();
        pendingAssignmentId = assignmentFor(cycleId, pendingEmployee.getId()).getId();

        List<EmployeeKpi> ownKpis = employeeKpiRepository.findByAssignment_IdOrderByIdAsc(assignmentId);
        firstEmployeeKpiId = ownKpis.get(0).getId();
        secondEmployeeKpiId = ownKpis.get(1).getId();
        List<EmployeeKpi> otherKpis = employeeKpiRepository.findByAssignment_IdOrderByIdAsc(otherAssignmentId);
        otherEmployeeKpiId = otherKpis.get(0).getId();

        // Eligible: self-assessment submitted and the manager has submitted
        // the (empty) manager review; both KPIs are HR-assessed.
        submitSelfAssessment(employee, assignmentId, 4, 5, "Delivered on time.", "Solid quality.");
        submitManagerAssessment(manager, assignmentId);

        // Manager review still pending: self-assessment only.
        submitSelfAssessment(otherEmployee, otherAssignmentId, 3, 4, "Other self.", "Other quality.");

        // A DRAFT cycle roster row must never be visible to HR.
        Long draftCycleId = cycleService.create(CycleTestData.request("Draft 2026"), hrUser.getId()).id();
        cycleService.configureKpis(draftCycleId, new ConfigureCycleKpisRequest(List.of(
                new CycleKpiSelectionRequest(deliveryMaster.getId(), null))));
        List<CycleEmployeeResponse> roster = cycleService.configureEmployees(draftCycleId,
                new ConfigureCycleEmployeesRequest(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()))));
        draftAssignmentId = roster.get(0).id();
    }

    // --------------------------------------------------------------- security

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/hr/assessments"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/hr/assessments/{id}", assignmentId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/hr/assessments/{id}/status", assignmentId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/hr/assessments/{id}/draft", assignmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draft(entry(firstEmployeeKpiId, 4, "ok")))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/hr/assessments/{id}/submit", assignmentId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void employeesAndManagersCannotUseHrAssessmentEndpoints() throws Exception {
        for (String token : List.of(employeeToken, managerToken)) {
            mockMvc.perform(withToken(get("/api/hr/assessments"), token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withToken(get("/api/hr/assessments/{id}", assignmentId), token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withToken(get("/api/hr/assessments/{id}/status", assignmentId), token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withToken(put("/api/hr/assessments/{id}/draft", assignmentId), token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(draft(entry(firstEmployeeKpiId, 4, "ok")))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withToken(post("/api/hr/assessments/{id}/submit", assignmentId), token))
                    .andExpect(status().isForbidden());
        }
        assertThat(hrAssessmentRepository.count()).isZero();
    }

    // ----------------------------------------------------------------- queue

    @Test
    void queueContainsOnlyHrReviewPendingAssignments() throws Exception {
        List<HrQueueItemResponse> queue = listQueue();

        assertThat(queue).hasSize(1);
        HrQueueItemResponse item = queue.get(0);
        assertThat(item.assignmentId()).isEqualTo(assignmentId);
        assertThat(item.employeeId()).isEqualTo(employee.getId());
        assertThat(item.employeeCode()).isEqualTo("EMP-HR-REV");
        assertThat(item.employeeName()).isEqualTo("Eve Employee");
        assertThat(item.department()).isEqualTo("Engineering");
        assertThat(item.designation()).isEqualTo("Engineer");
        assertThat(item.cycleId()).isEqualTo(cycleId);
        assertThat(item.cycleName()).isEqualTo("Annual 2026");
        assertThat(item.managerSubmittedAt()).isNotNull();
        assertThat(item.assignmentStatus()).isEqualTo("HR_REVIEW_PENDING");
    }

    @Test
    void queueExcludesManagerPendingAndUnsubmittedAssignments() throws Exception {
        assertThat(listQueue())
                .extracting(HrQueueItemResponse::assignmentId)
                .containsExactly(assignmentId)
                .doesNotContain(otherAssignmentId, pendingAssignmentId, draftAssignmentId);
    }

    @Test
    void queueIsOrderedByMostRecentManagerSubmission() throws Exception {
        // The second employee's manager submits later, so that row is first.
        submitManagerAssessment(otherManager, otherAssignmentId);

        List<HrQueueItemResponse> queue = listQueue();
        assertThat(queue).hasSize(2);
        assertThat(queue.get(0).assignmentId()).isEqualTo(otherAssignmentId);
        assertThat(queue.get(1).assignmentId()).isEqualTo(assignmentId);
    }

    @Test
    void onlyPublishedCyclesAreVisible() throws Exception {
        for (CycleStatus status : List.of(CycleStatus.IN_PROGRESS, CycleStatus.COMPLETED)) {
            setCycleStatus(cycleId, status);

            assertThat(listQueue()).isEmpty();

            mockMvc.perform(authed(get("/api/hr/assessments/{id}", assignmentId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("HR assessment not found."));
            mockMvc.perform(authed(get("/api/hr/assessments/{id}/status", assignmentId)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(authed(put("/api/hr/assessments/{id}/draft", assignmentId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(draft(entry(firstEmployeeKpiId, 3, "Hidden")))))
                    .andExpect(status().isNotFound());
            mockMvc.perform(authed(post("/api/hr/assessments/{id}/submit", assignmentId)))
                    .andExpect(status().isNotFound());

            assertThat(hrAssessmentRepository.count()).isZero();
        }
    }

    @Test
    void ineligibleAssignmentsReturnTheSameNotFound() throws Exception {
        for (Long hidden : List.of(otherAssignmentId, pendingAssignmentId, draftAssignmentId, 999_999L)) {
            mockMvc.perform(authed(get("/api/hr/assessments/{id}", hidden)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("HR assessment not found."));
            mockMvc.perform(authed(get("/api/hr/assessments/{id}/status", hidden)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(authed(put("/api/hr/assessments/{id}/draft", hidden))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(draft(entry(firstEmployeeKpiId, 3, "Hidden")))))
                    .andExpect(status().isNotFound());
            mockMvc.perform(authed(post("/api/hr/assessments/{id}/submit", hidden)))
                    .andExpect(status().isNotFound());
        }

        assertThat(hrAssessmentRepository.count()).isZero();
        assertThat(assignmentRepository.findById(otherAssignmentId).orElseThrow().getStatus())
                .isEqualTo(AssignmentStatus.MANAGER_REVIEW_PENDING);
        assertThat(assignmentRepository.findById(pendingAssignmentId).orElseThrow().getStatus())
                .isEqualTo(AssignmentStatus.SELF_ASSESSMENT_PENDING);
    }

    // ---------------------------------------------------------------- detail

    @Test
    void detailReturnsAllThreePerspectivesAndFrozenKpis() throws Exception {
        HrAssessmentResponse detail = getDetail(assignmentId);

        assertThat(detail.assignmentId()).isEqualTo(assignmentId);
        assertThat(detail.cycleId()).isEqualTo(cycleId);
        assertThat(detail.cycleName()).isEqualTo("Annual 2026");
        assertThat(detail.cycleStatus()).isEqualTo("PUBLISHED");
        assertThat(detail.assignmentStatus()).isEqualTo("HR_REVIEW_PENDING");
        assertThat(detail.editable()).isTrue();
        assertThat(detail.selfSubmittedAt()).isNotNull();
        assertThat(detail.managerSubmittedAt()).isNotNull();
        assertThat(detail.hrFinalizedAt()).isNull();
        assertThat(detail.hrReviewDeadline()).isEqualTo(java.time.LocalDate.of(2026, 12, 31));

        assertThat(detail.employee().employeeId()).isEqualTo(employee.getId());
        assertThat(detail.employee().employeeCode()).isEqualTo("EMP-HR-REV");
        assertThat(detail.employee().fullName()).isEqualTo("Eve Employee");
        assertThat(detail.employee().department()).isEqualTo("Engineering");
        assertThat(detail.employee().designation()).isEqualTo("Engineer");

        assertThat(detail.kpis()).hasSize(2);
        HrAssessmentKpiResponse delivery = detail.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(firstEmployeeKpiId))
                .findFirst().orElseThrow();
        assertThat(delivery.kpiId()).isEqualTo(deliveryMaster.getId());
        assertThat(delivery.name()).isEqualTo("Delivery Quality");
        assertThat(delivery.description()).isEqualTo("Meets agreed deadlines.");
        assertThat(delivery.category()).isEqualTo("Delivery");
        assertThat(delivery.weightage()).isEqualByComparingTo("60.00");
        assertThat(delivery.selfRating()).isEqualTo(4);
        assertThat(delivery.selfComment()).isEqualTo("Delivered on time.");
        assertThat(delivery.managerRating()).isEqualTo(4);
        assertThat(delivery.managerComment()).isEqualTo("Manager review.");
        assertThat(delivery.hrRating()).isNull();
        assertThat(delivery.hrComment()).isNull();

        HrAssessmentKpiResponse quality = detail.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(secondEmployeeKpiId))
                .findFirst().orElseThrow();
        assertThat(quality.selfRating()).isEqualTo(5);
        assertThat(quality.managerRating()).isEqualTo(4);
        assertThat(quality.weightage()).isEqualByComparingTo("40.00");

        // The response must not leak account details or a computed score.
        String body = mockMvc.perform(authed(get("/api/hr/assessments/{id}", assignmentId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("evalora.local")
                .doesNotContain("passwordHash")
                .doesNotContain("overallScore")
                .doesNotContain("finalScore");
    }

    @Test
    void detailIsReadOnlyAfterSubmission() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 4, "Delivery."), entry(secondEmployeeKpiId, 5, "Quality."));
        submit();

        HrAssessmentResponse detail = getDetail(assignmentId);
        assertThat(detail.assignmentStatus()).isEqualTo("COMPLETED");
        assertThat(detail.editable()).isFalse();
        assertThat(detail.hrFinalizedAt()).isNotNull();
        assertThat(hrRatingOf(detail, firstEmployeeKpiId)).isEqualTo(4);
        assertThat(hrCommentOf(detail, firstEmployeeKpiId)).isEqualTo("Delivery.");
    }

    // ----------------------------------------------------------------- draft

    @Test
    void savesAndRetrievesADraft() throws Exception {
        HrAssessmentResponse saved = saveDraft(
                entry(firstEmployeeKpiId, 4, "  Consistently meets.  "),
                entry(secondEmployeeKpiId, 5, "Exceeds quality bar."));

        assertThat(saved.editable()).isTrue();
        assertThat(hrRatingOf(saved, firstEmployeeKpiId)).isEqualTo(4);
        assertThat(hrCommentOf(saved, firstEmployeeKpiId)).isEqualTo("Consistently meets.");
        assertThat(hrRatingOf(saved, secondEmployeeKpiId)).isEqualTo(5);

        HrAssessmentResponse reloaded = getDetail(assignmentId);
        assertThat(hrRatingOf(reloaded, firstEmployeeKpiId)).isEqualTo(4);
        assertThat(hrCommentOf(reloaded, firstEmployeeKpiId)).isEqualTo("Consistently meets.");

        // The other perspectives are untouched.
        assertThat(selfRatingOf(reloaded, firstEmployeeKpiId)).isEqualTo(4);
        assertThat(managerRatingOf(reloaded, firstEmployeeKpiId)).isEqualTo(4);

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.HR_REVIEW_PENDING);
        assertThat(assignment.getHrFinalizedAt()).isNull();
    }

    @Test
    void savesAPartialDraft() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 3, null));

        HrAssessmentStatusResponse status = getStatus(assignmentId);
        assertThat(status.totalKpis()).isEqualTo(2);
        assertThat(status.ratedKpis()).isEqualTo(1);
        assertThat(status.submitted()).isFalse();
        assertThat(status.hrFinalizedAt()).isNull();
    }

    @Test
    void blankCommentBecomesNull() throws Exception {
        HrAssessmentResponse saved = saveDraft(entry(firstEmployeeKpiId, 4, "   "));

        assertThat(hrCommentOf(saved, firstEmployeeKpiId)).isNull();
        assertThat(hrAssessmentRepository.findByEmployeeKpi_Id(firstEmployeeKpiId).orElseThrow()
                .getComment()).isNull();
    }

    @Test
    void emptyDraftIsAccepted() throws Exception {
        mockMvc.perform(authed(put("/api/hr/assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kpis", List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(true));

        assertThat(hrAssessmentRepository.count()).isZero();
    }

    @Test
    void repeatedDraftSavesUpdateTheSameRowsAndPreserveOmittedKpis() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 2, "First pass."),
                entry(secondEmployeeKpiId, 3, "First pass."));
        saveDraft(entry(firstEmployeeKpiId, 5, "Second pass."));

        assertThat(hrAssessmentRepository.count()).isEqualTo(2);
        HrAssessmentResponse detail = getDetail(assignmentId);
        assertThat(hrRatingOf(detail, firstEmployeeKpiId)).isEqualTo(5);
        assertThat(hrCommentOf(detail, firstEmployeeKpiId)).isEqualTo("Second pass.");
        assertThat(hrRatingOf(detail, secondEmployeeKpiId)).isEqualTo(3);
        assertThat(hrCommentOf(detail, secondEmployeeKpiId)).isEqualTo("First pass.");
    }

    @Test
    void draftRejectsRatingsOutsideOneToFive() throws Exception {
        expectBadRequest(List.of(entry(firstEmployeeKpiId, 0, null)), INVALID_RATING_MESSAGE);
        expectBadRequest(List.of(entry(firstEmployeeKpiId, 6, null)), INVALID_RATING_MESSAGE);
        assertThat(hrAssessmentRepository.count()).isZero();
    }

    @Test
    void draftRejectsDecimalRating() throws Exception {
        expectBadRequest(List.of(entry(firstEmployeeKpiId, 3.5, null)), INVALID_RATING_MESSAGE);
        assertThat(hrAssessmentRepository.count()).isZero();
    }

    @Test
    void draftRejectsDuplicateKpiIds() throws Exception {
        expectBadRequest(List.of(entry(firstEmployeeKpiId, 3, null), entry(firstEmployeeKpiId, 4, null)),
                "The same KPI cannot be rated twice in one request.");
        assertThat(hrAssessmentRepository.count()).isZero();
    }

    @Test
    void draftRejectsKpiFromAnotherAssignment() throws Exception {
        expectBadRequest(List.of(entry(otherEmployeeKpiId, 3, null)),
                "The selected KPI does not belong to this assessment.");
        assertThat(hrAssessmentRepository.count()).isZero();
    }

    @Test
    void hrCannotRateAManagerAssessedKpi() throws Exception {
        EmployeeKpi managerKpi = employeeKpiRepository.findById(firstEmployeeKpiId).orElseThrow();
        managerKpi.setKpiType(KpiType.MANAGER);
        employeeKpiRepository.saveAndFlush(managerKpi);

        expectBadRequest(List.of(entry(firstEmployeeKpiId, 4, "Not mine.")),
                "HR can only assess HR-assessed KPIs.");
        assertThat(hrAssessmentRepository.count()).isZero();
    }

    @Test
    void draftRejectsMissingEntries() throws Exception {
        mockMvc.perform(authed(put("/api/hr/assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("HR assessment entries are required."));
    }

    @Test
    void invalidDraftDoesNotPartiallyModifySavedData() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 4, "Keep me."));

        mockMvc.perform(authed(put("/api/hr/assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draft(entry(firstEmployeeKpiId, 5, "Should not stick."),
                                entry(secondEmployeeKpiId, 9, null)))))
                .andExpect(status().isBadRequest());

        HrAssessmentResponse detail = getDetail(assignmentId);
        assertThat(hrRatingOf(detail, firstEmployeeKpiId)).isEqualTo(4);
        assertThat(hrCommentOf(detail, firstEmployeeKpiId)).isEqualTo("Keep me.");
        assertThat(hrRatingOf(detail, secondEmployeeKpiId)).isNull();
    }

    @Test
    void draftIgnoresClientSuppliedIdentity() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kpis", List.of(entry(firstEmployeeKpiId, 4, "Mine.")));
        body.put("hrId", manager.getUser().getId());
        body.put("employeeId", otherEmployee.getId());
        body.put("assignmentId", otherAssignmentId);

        mockMvc.perform(authed(put("/api/hr/assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk());

        List<HrAssessment> rows = hrAssessmentRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getHr().getId()).isEqualTo(hrUser.getId());
        assertThat(rows.get(0).getEmployeeKpi().getId()).isEqualTo(firstEmployeeKpiId);
        assertThat(hrAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId))
                .hasSize(1);
    }

    // -------------------------------------------------------------- submission

    @Test
    void submissionRequiresEveryKpiToBeRated() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 4, null));

        mockMvc.perform(authed(post("/api/hr/assessments/{id}/submit", assignmentId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Every HR-assessed KPI must have an HR rating from 1 to 5 before submitting."));

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.HR_REVIEW_PENDING);
        assertThat(assignment.getHrFinalizedAt()).isNull();
        assertThat(resultRepository.count()).isZero();
        assertThat(resultItemRepository.count()).isZero();
    }

    @Test
    void submissionCompletesTheAssignmentAndStampsFinalization() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 4, "Delivery."), entry(secondEmployeeKpiId, 5, "Quality."));

        SelfAssessment beforeSelf =
                selfAssessmentRepository.findByEmployeeKpi_Id(firstEmployeeKpiId).orElseThrow();
        java.time.OffsetDateTime managerSubmittedAt = assignmentRepository.findById(assignmentId)
                .orElseThrow().getManagerSubmittedAt();

        HrAssessmentStatusResponse submitted = submit();
        assertThat(submitted.submitted()).isTrue();
        assertThat(submitted.assignmentStatus()).isEqualTo("COMPLETED");
        assertThat(submitted.hrFinalizedAt()).isNotNull();
        assertThat(submitted.ratedKpis()).isEqualTo(2);

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(assignment.getHrFinalizedAt()).isNotNull();
        assertThat(assignment.getResultsPublishedAt()).isNull();
        assertThat(assignment.getManagerSubmittedAt()).isEqualTo(managerSubmittedAt);

        // Completion generates the result but does not publish it.
        assertThat(cycleRepository.findById(cycleId).orElseThrow().getStatus())
                .isEqualTo(CycleStatus.PUBLISHED);
        PerformanceResult result = resultRepository.findByAssignment_Id(assignmentId).orElseThrow();
        assertThat(result.getOverallScore()).isEqualByComparingTo("4.20");
        assertThat(result.getCalculationMode()).isEqualTo(CalculationMode.WEIGHTED);
        assertThat(result.getGrade()).isEqualTo(PerformanceGrade.EXCELLENT);
        assertThat(result.getStatus()).isEqualTo(ResultStatus.DRAFT);
        assertThat(result.getPublishedAt()).isNull();
        assertThat(result.getPublishedBy()).isNull();
        assertThat(result.getComputedAt()).isNotNull();
        assertThat(resultItemRepository.findByResult_Id(result.getId())).hasSize(2);

        // The employee and manager assessments are exactly as they were.
        // Manager assessments exist for every KPI and remain untouched by HR.
        assertThat(managerAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId)).hasSize(2);
        SelfAssessment afterSelf =
                selfAssessmentRepository.findByEmployeeKpi_Id(firstEmployeeKpiId).orElseThrow();
        assertThat(afterSelf.getRating()).isEqualTo(beforeSelf.getRating());
        assertThat(afterSelf.getComment()).isEqualTo(beforeSelf.getComment());
    }

    @Test
    void submittedAssessmentLeavesThePendingQueue() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));
        assertThat(listQueue())
                .extracting(HrQueueItemResponse::assignmentId)
                .contains(assignmentId);

        submit();

        assertThat(listQueue()).isEmpty();
    }

    @Test
    void submissionTwiceIsRejected() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));
        submit();

        mockMvc.perform(authed(post("/api/hr/assessments/{id}/submit", assignmentId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("The HR assessment has already been submitted."));

        assertThat(resultRepository.count()).isEqualTo(1);
        PerformanceResult result = resultRepository.findByAssignment_Id(assignmentId).orElseThrow();
        assertThat(resultItemRepository.findByResult_Id(result.getId())).hasSize(2);
    }

    @Test
    void draftCannotBeChangedAfterSubmission() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));
        submit();

        mockMvc.perform(authed(put("/api/hr/assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draft(entry(firstEmployeeKpiId, 1, "Too late")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("The HR assessment has already been submitted."));

        HrAssessmentResponse detail = getDetail(assignmentId);
        assertThat(detail.editable()).isFalse();
        assertThat(hrRatingOf(detail, firstEmployeeKpiId)).isEqualTo(4);
    }

    @Test
    void statusTracksDraftProgress() throws Exception {
        HrAssessmentStatusResponse initial = getStatus(assignmentId);
        assertThat(initial.totalKpis()).isEqualTo(2);
        assertThat(initial.ratedKpis()).isZero();
        assertThat(initial.submitted()).isFalse();

        saveDraft(entry(firstEmployeeKpiId, 3, null));
        assertThat(getStatus(assignmentId).ratedKpis()).isEqualTo(1);

        saveDraft(entry(secondEmployeeKpiId, 4, null));
        assertThat(getStatus(assignmentId).ratedKpis()).isEqualTo(2);

        submit();
        HrAssessmentStatusResponse afterSubmit = getStatus(assignmentId);
        assertThat(afterSubmit.submitted()).isTrue();
        assertThat(afterSubmit.assignmentStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void resultIsPersistedButNotExposedThroughTheHrApi() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 4, "Delivery."), entry(secondEmployeeKpiId, 5, "Quality."));
        submit();

        PerformanceResult result = resultRepository.findByAssignment_Id(assignmentId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(ResultStatus.DRAFT);
        assertThat(result.getPublishedAt()).isNull();
        List<PerformanceResultItem> items = resultItemRepository.findByResult_Id(result.getId());
        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.getHrRating()).isNotNull();
            assertThat(item.getManagerRating()).isEqualTo((short) 4);
            assertThat(item.getFinalScore()).isBetween(
                    new BigDecimal("1.00"), new BigDecimal("5.00"));
        });

        String body = mockMvc.perform(authed(get("/api/hr/assessments/{id}", assignmentId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("overallScore")
                .doesNotContain("finalScore")
                .doesNotContain("weightedScore")
                .doesNotContain("grade");
    }

    // ----------------------------------------------------------------- helpers

    private HrAssessmentResponse getDetail(Long id) throws Exception {
        MvcResult result = mockMvc.perform(authed(get("/api/hr/assessments/{id}", id)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                HrAssessmentResponse.class);
    }

    private HrAssessmentStatusResponse getStatus(Long id) throws Exception {
        MvcResult result = mockMvc.perform(authed(get("/api/hr/assessments/{id}/status", id)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                HrAssessmentStatusResponse.class);
    }

    private List<HrQueueItemResponse> listQueue() throws Exception {
        MvcResult result = mockMvc.perform(authed(get("/api/hr/assessments")))
                .andExpect(status().isOk())
                .andReturn();
        return List.of(objectMapper.readValue(result.getResponse().getContentAsString(),
                HrQueueItemResponse[].class));
    }

    private HrAssessmentResponse saveDraft(Map<String, Object>... entries) throws Exception {
        return saveDraft(List.of(entries));
    }

    private HrAssessmentResponse saveDraft(List<Map<String, Object>> entries) throws Exception {
        MvcResult result = mockMvc.perform(authed(put("/api/hr/assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kpis", entries))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                HrAssessmentResponse.class);
    }

    private HrAssessmentStatusResponse submit() throws Exception {
        MvcResult result = mockMvc.perform(authed(post("/api/hr/assessments/{id}/submit", assignmentId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                HrAssessmentStatusResponse.class);
    }

    private void expectBadRequest(List<Map<String, Object>> entries, String message) throws Exception {
        mockMvc.perform(authed(put("/api/hr/assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kpis", entries))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(message));
    }

    private static final String INVALID_RATING_MESSAGE =
            "HR assessment rating must be a whole number between 1 and 5.";

    private static Map<String, Object> entry(Long employeeKpiId, Object rating, String comment) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("employeeKpiId", employeeKpiId);
        entry.put("rating", rating);
        entry.put("comment", comment);
        return entry;
    }

    @SafeVarargs
    private static Map<String, Object> draft(Map<String, Object>... entries) {
        return Map.of("kpis", List.of(entries));
    }

    private static Integer hrRatingOf(HrAssessmentResponse response, Long employeeKpiId) {
        return response.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(employeeKpiId))
                .findFirst().orElseThrow().hrRating();
    }

    private static String hrCommentOf(HrAssessmentResponse response, Long employeeKpiId) {
        return response.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(employeeKpiId))
                .findFirst().orElseThrow().hrComment();
    }

    private static Integer managerRatingOf(HrAssessmentResponse response, Long employeeKpiId) {
        return response.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(employeeKpiId))
                .findFirst().orElseThrow().managerRating();
    }

    private static Integer selfRatingOf(HrAssessmentResponse response, Long employeeKpiId) {
        return response.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(employeeKpiId))
                .findFirst().orElseThrow().selfRating();
    }

    private void submitSelfAssessment(Employee owner, Long id, int first, int second,
                                      String firstComment, String secondComment) {
        List<EmployeeKpi> kpis = employeeKpiRepository.findByAssignment_IdOrderByIdAsc(id);
        selfAssessmentService.saveDraft(owner.getUser().getId(), id,
                new SaveSelfAssessmentDraftRequest(List.of(
                        new SelfAssessmentEntryRequest(kpis.get(0).getId(),
                                new BigDecimal(first), firstComment),
                        new SelfAssessmentEntryRequest(kpis.get(1).getId(),
                                new BigDecimal(second), secondComment))));
        selfAssessmentService.submit(owner.getUser().getId(), id);
    }

    private void submitManagerAssessment(Employee reviewer, Long id) {
        List<ManagerAssessmentEntryRequest> ratings = employeeKpiRepository
                .findByAssignment_IdOrderByIdAsc(id).stream()
                .map(kpi -> new ManagerAssessmentEntryRequest(kpi.getId(), new BigDecimal("4"), "Manager review."))
                .toList();
        managerAssessmentService.saveDraft(reviewer.getUser().getId(), id,
                new SaveManagerAssessmentDraftRequest(ratings));
        managerAssessmentService.submit(reviewer.getUser().getId(), id);
    }

    private EmployeeAssignment assignmentFor(Long cycleId, Long employeeId) {
        return assignmentRepository.findByCycle_IdOrderByEmployee_IdAsc(cycleId).stream()
                .filter(assignment -> assignment.getEmployee().getId().equals(employeeId))
                .findFirst()
                .orElseThrow();
    }

    /** Moves a cycle on without going through the HR API (status is HR-owned). */
    private void setCycleStatus(Long id, CycleStatus status) {
        PerformanceCycle cycle = cycleRepository.findById(id).orElseThrow();
        cycle.setStatus(status);
        cycleRepository.saveAndFlush(cycle);
    }

    private Employee profile(Role role, String code, String name) {
        User user = userRepository.saveAndFlush(
                CycleTestData.user(code.toLowerCase() + "@evalora.local", role));
        return employeeRepository.saveAndFlush(CycleTestData.profile(user, code, name));
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return withToken(builder, hrToken);
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder builder, String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
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
