package com.evalora.backend.controller;

import com.evalora.backend.dto.ConfigureCycleEmployeesRequest;
import com.evalora.backend.dto.ConfigureCycleKpisRequest;
import com.evalora.backend.dto.CycleEmployeeResponse;
import com.evalora.backend.dto.CycleEmployeeSelectionRequest;
import com.evalora.backend.dto.CycleKpiSelectionRequest;
import com.evalora.backend.dto.ManagerAssessmentKpiResponse;
import com.evalora.backend.dto.ManagerAssessmentResponse;
import com.evalora.backend.dto.ManagerAssessmentStatusResponse;
import com.evalora.backend.dto.ManagerQueueItemResponse;
import com.evalora.backend.dto.SaveSelfAssessmentDraftRequest;
import com.evalora.backend.dto.SelfAssessmentEntryRequest;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.EmployeeAssignment;
import com.evalora.backend.entity.EmployeeKpi;
import com.evalora.backend.entity.KpiMaster;
import com.evalora.backend.entity.ManagerAssessment;
import com.evalora.backend.entity.PerformanceCycle;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.AssignmentStatus;
import com.evalora.backend.entity.enums.CycleStatus;
import com.evalora.backend.entity.enums.KpiType;
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
import com.evalora.backend.repository.PerformanceResultRepository;
import com.evalora.backend.repository.SelfAssessmentRepository;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.security.JwtService;
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
 * End-to-end manager assessment through the real filter chain, controller,
 * service and H2 in PostgreSQL mode.
 *
 * <p>H2 proves the JPA behaviour and the HTTP contract. It does not prove
 * PostgreSQL-specific index or row-lock behaviour.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_manager_assessment;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000"
})
class ManagerAssessmentIntegrationTest {

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
        private PerformanceResultRepository performanceResultRepository;

    @Autowired
    private KpiMasterRepository kpiRepository;

    @Autowired
    private PerformanceCycleService cycleService;

    @Autowired
    private SelfAssessmentService selfAssessmentService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private User hrUser;
    private Employee manager;
    private Employee otherManager;
    private Employee employee;
    private Employee otherEmployee;
    private Employee unsubmittedEmployee;
    private KpiMaster deliveryMaster;
    private KpiMaster qualityMaster;

    private String hrToken;
    private String managerToken;
    private String otherManagerToken;
    private String employeeToken;

    private Long cycleId;
    private Long assignmentId;
    private Long otherAssignmentId;
    private Long unsubmittedAssignmentId;
    private Long firstEmployeeKpiId;
    private Long secondEmployeeKpiId;
    private Long otherEmployeeKpiId;
    private Long draftAssignmentId;

    @BeforeEach
    void setUp() {
        clearDatabase();

        hrUser = userRepository.saveAndFlush(CycleTestData.user("hr.manager@evalora.local", Role.HR));
        manager = profile(Role.MANAGER, "MGR-REV", "Mona Manager");
        otherManager = profile(Role.MANAGER, "MGR-OTHER", "Nate Other");
        employee = profile(Role.EMPLOYEE, "EMP-REV", "Eve Employee");
        otherEmployee = profile(Role.EMPLOYEE, "EMP-REV-2", "Oscar Other");
        unsubmittedEmployee = profile(Role.EMPLOYEE, "EMP-REV-3", "Dana Delay");

        deliveryMaster = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Delivery Quality", "Meets agreed deadlines.", "Delivery"));
        qualityMaster = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Quality", "Work meets the quality bar.", "Quality"));

        hrToken = jwtService.generateToken(hrUser);
        managerToken = jwtService.generateToken(manager.getUser());
        otherManagerToken = jwtService.generateToken(otherManager.getUser());
        employeeToken = jwtService.generateToken(employee.getUser());

        cycleId = cycleService.create(CycleTestData.request("Annual 2026"), hrUser.getId()).id();
        cycleService.configureKpis(cycleId, new ConfigureCycleKpisRequest(List.of(
                new CycleKpiSelectionRequest(deliveryMaster.getId(), new BigDecimal("60.00")),
                new CycleKpiSelectionRequest(qualityMaster.getId(), new BigDecimal("40.00")))));
        cycleService.configureEmployees(cycleId, new ConfigureCycleEmployeesRequest(List.of(
                new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()),
                new CycleEmployeeSelectionRequest(otherEmployee.getId(), otherManager.getId()),
                new CycleEmployeeSelectionRequest(unsubmittedEmployee.getId(), manager.getId()))));
        cycleService.publish(cycleId);

        assignmentId = assignmentFor(cycleId, employee.getId()).getId();
        otherAssignmentId = assignmentFor(cycleId, otherEmployee.getId()).getId();
        unsubmittedAssignmentId = assignmentFor(cycleId, unsubmittedEmployee.getId()).getId();

        List<EmployeeKpi> ownKpis = employeeKpiRepository.findByAssignment_IdOrderByIdAsc(assignmentId);
        firstEmployeeKpiId = ownKpis.get(0).getId();
        secondEmployeeKpiId = ownKpis.get(1).getId();
        otherEmployeeKpiId = employeeKpiRepository.findByAssignment_IdOrderByIdAsc(otherAssignmentId)
                .get(0).getId();

        // The eligible assignment: the employee finished and submitted.
        submitSelfAssessment(employee, assignmentId, 4, 5, "Delivered on time.", "Solid quality.");
        submitSelfAssessment(otherEmployee, otherAssignmentId, 3, 4, "Other self.", "Other quality.");

        // A published cycle that was reconfigured to be a DRAFT roster row is
        // not possible, so a separate DRAFT cycle with a roster row is created.
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
        mockMvc.perform(get("/api/manager/assessments"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/manager/assessments/{id}", assignmentId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/manager/assessments/{id}/status", assignmentId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/manager/assessments/{id}/draft", assignmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draft(entry(firstEmployeeKpiId, 4, "ok")))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/manager/assessments/{id}/submit", assignmentId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void employeesAndHrCannotUseManagerEndpoints() throws Exception {
        for (String token : List.of(employeeToken, hrToken)) {
            mockMvc.perform(withToken(get("/api/manager/assessments"), token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withToken(get("/api/manager/assessments/{id}", assignmentId), token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withToken(get("/api/manager/assessments/{id}/status", assignmentId), token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withToken(put("/api/manager/assessments/{id}/draft", assignmentId), token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(draft(entry(firstEmployeeKpiId, 4, "ok")))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withToken(post("/api/manager/assessments/{id}/submit", assignmentId), token))
                    .andExpect(status().isForbidden());
        }
        assertThat(managerAssessmentRepository.count()).isZero();
    }

    @Test
    void anotherManagersAssignmentIsNotFound() throws Exception {
        mockMvc.perform(withToken(get("/api/manager/assessments/{id}", assignmentId), otherManagerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Manager assessment not found."));
        mockMvc.perform(withToken(get("/api/manager/assessments/{id}/status", assignmentId), otherManagerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Manager assessment not found."));
        mockMvc.perform(withToken(put("/api/manager/assessments/{id}/draft", assignmentId), otherManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draft(entry(firstEmployeeKpiId, 5, "Not mine")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Manager assessment not found."));
        mockMvc.perform(withToken(post("/api/manager/assessments/{id}/submit", assignmentId), otherManagerToken))
                .andExpect(status().isNotFound());

        assertThat(managerAssessmentRepository.count()).isZero();
    }

    @Test
    void assignmentsWithUnsubmittedSelfAssessmentOrDraftCycleAreHidden() throws Exception {
        for (Long hidden : List.of(unsubmittedAssignmentId, draftAssignmentId, 999_999L)) {
            mockMvc.perform(authed(get("/api/manager/assessments/{id}", hidden)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Manager assessment not found."));
            mockMvc.perform(authed(get("/api/manager/assessments/{id}/status", hidden)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(authed(put("/api/manager/assessments/{id}/draft", hidden))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(draft(entry(firstEmployeeKpiId, 3, "Hidden")))))
                    .andExpect(status().isNotFound());
            mockMvc.perform(authed(post("/api/manager/assessments/{id}/submit", hidden)))
                    .andExpect(status().isNotFound());
        }

        assertThat(managerAssessmentRepository.count()).isZero();
        assertThat(assignmentRepository.findById(unsubmittedAssignmentId).orElseThrow().getStatus())
                .isEqualTo(AssignmentStatus.SELF_ASSESSMENT_PENDING);
    }

    @Test
    void onlyPublishedCyclesAreVisible() throws Exception {
        for (CycleStatus status : List.of(CycleStatus.IN_PROGRESS, CycleStatus.COMPLETED)) {
            setCycleStatus(cycleId, status);

            assertThat(listQueue(managerToken)).isEmpty();

            mockMvc.perform(authed(get("/api/manager/assessments/{id}", assignmentId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Manager assessment not found."));
            mockMvc.perform(authed(get("/api/manager/assessments/{id}/status", assignmentId)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(authed(put("/api/manager/assessments/{id}/draft", assignmentId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(draft(entry(firstEmployeeKpiId, 3, "Hidden")))))
                    .andExpect(status().isNotFound());
            mockMvc.perform(authed(post("/api/manager/assessments/{id}/submit", assignmentId)))
                    .andExpect(status().isNotFound());

            assertThat(managerAssessmentRepository.count()).isZero();
        }
    }

    @Test
    void managersCanRateHrAssessedKpis() throws Exception {
        EmployeeKpi hrKpi = employeeKpiRepository.findById(firstEmployeeKpiId).orElseThrow();
        hrKpi.setKpiType(KpiType.HR);
        employeeKpiRepository.saveAndFlush(hrKpi);

        mockMvc.perform(authed(put("/api/manager/assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draft(entry(firstEmployeeKpiId, 4, "Manager HR KPI rating")))))
                .andExpect(status().isOk());

        assertThat(managerAssessmentRepository.count()).isEqualTo(1);
        assertThat(managerRatingOf(getDetail(assignmentId), firstEmployeeKpiId)).isEqualTo(4);
    }

    // ----------------------------------------------------------------- queue

    @Test
    void queueContainsOnlyTheManagersOwnSubmittedAssignments() throws Exception {
                employee.setManager(otherManager);
                employeeRepository.saveAndFlush(employee);

        List<ManagerQueueItemResponse> queue = listQueue(managerToken);

        assertThat(queue).hasSize(1);
        ManagerQueueItemResponse item = queue.get(0);
        assertThat(item.assignmentId()).isEqualTo(assignmentId);
        assertThat(item.employeeId()).isEqualTo(employee.getId());
        assertThat(item.employeeCode()).isEqualTo("EMP-REV");
        assertThat(item.employeeName()).isEqualTo("Eve Employee");
        assertThat(item.cycleId()).isEqualTo(cycleId);
        assertThat(item.cycleName()).isEqualTo("Annual 2026");
        assertThat(item.selfSubmittedAt()).isNotNull();
        assertThat(item.assignmentStatus()).isEqualTo("MANAGER_REVIEW_PENDING");

        List<ManagerQueueItemResponse> otherQueue = listQueue(otherManagerToken);
        assertThat(otherQueue).hasSize(1);
        assertThat(otherQueue.get(0).assignmentId()).isEqualTo(otherAssignmentId);
    }

    @Test
    void queueExcludesUnsubmittedAssignmentsAndSubmittedReviews() throws Exception {
        assertThat(listQueue(managerToken))
                .extracting(ManagerQueueItemResponse::assignmentId)
                .doesNotContain(unsubmittedAssignmentId);

        saveDraft(entry(firstEmployeeKpiId, 4, "ok"), entry(secondEmployeeKpiId, 5, "ok"));
        submit();

        assertThat(listQueue(managerToken)).isEmpty();
    }

    @Test
    void queueIsOrderedByMostRecentSubmission() throws Exception {
        // A second published cycle gives the same manager a second eligible
        // assignment whose employee submits later than the one in setUp().
        // A published cycle cannot be reconfigured, so this must be a new one.
        Employee late = profile(Role.EMPLOYEE, "EMP-REV-4", "Late Larry");
        Long lateCycleId = cycleService.create(CycleTestData.request("Mid-Year 2026"), hrUser.getId()).id();
        cycleService.configureKpis(lateCycleId, new ConfigureCycleKpisRequest(List.of(
                new CycleKpiSelectionRequest(deliveryMaster.getId(), new BigDecimal("60.00")),
                new CycleKpiSelectionRequest(qualityMaster.getId(), new BigDecimal("40.00")))));
        cycleService.configureEmployees(lateCycleId, new ConfigureCycleEmployeesRequest(List.of(
                new CycleEmployeeSelectionRequest(late.getId(), manager.getId()))));
        cycleService.publish(lateCycleId);

        Long lateAssignmentId = assignmentFor(lateCycleId, late.getId()).getId();
        submitSelfAssessment(late, lateAssignmentId, 3, 3, null, null);

        List<ManagerQueueItemResponse> queue = listQueue(managerToken);
        assertThat(queue).hasSize(2);
        assertThat(queue.get(0).assignmentId()).isEqualTo(lateAssignmentId);
        assertThat(queue.get(1).assignmentId()).isEqualTo(assignmentId);
    }

    // ---------------------------------------------------------------- detail

    @Test
    void detailReturnsEmployeeFrozenKpisAndTheSelfAssessment() throws Exception {
        ManagerAssessmentResponse detail = getDetail(assignmentId);

        assertThat(detail.assignmentId()).isEqualTo(assignmentId);
        assertThat(detail.cycleId()).isEqualTo(cycleId);
        assertThat(detail.cycleName()).isEqualTo("Annual 2026");
        assertThat(detail.cycleStatus()).isEqualTo("PUBLISHED");
        assertThat(detail.assignmentStatus()).isEqualTo("MANAGER_REVIEW_PENDING");
        assertThat(detail.editable()).isTrue();
        assertThat(detail.selfSubmittedAt()).isNotNull();
        assertThat(detail.managerSubmittedAt()).isNull();
        assertThat(detail.managerReviewDeadline())
                .isEqualTo(java.time.LocalDate.of(2026, 12, 15));

        assertThat(detail.employee().employeeId()).isEqualTo(employee.getId());
        assertThat(detail.employee().employeeCode()).isEqualTo("EMP-REV");
        assertThat(detail.employee().fullName()).isEqualTo("Eve Employee");
        assertThat(detail.employee().department()).isEqualTo("Engineering");
        assertThat(detail.employee().designation()).isEqualTo("Engineer");

        assertThat(detail.kpis()).hasSize(2);
        ManagerAssessmentKpiResponse delivery = detail.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(firstEmployeeKpiId))
                .findFirst().orElseThrow();
        assertThat(delivery.kpiId()).isEqualTo(deliveryMaster.getId());
        assertThat(delivery.name()).isEqualTo("Delivery Quality");
        assertThat(delivery.description()).isEqualTo("Meets agreed deadlines.");
        assertThat(delivery.category()).isEqualTo("Delivery");
        assertThat(delivery.weightage()).isEqualByComparingTo("60.00");
        assertThat(delivery.selfRating()).isEqualTo(4);
        assertThat(delivery.selfComment()).isEqualTo("Delivered on time.");
        assertThat(delivery.managerRating()).isNull();
        assertThat(delivery.managerComment()).isNull();

        ManagerAssessmentKpiResponse quality = detail.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(secondEmployeeKpiId))
                .findFirst().orElseThrow();
        assertThat(quality.selfRating()).isEqualTo(5);
        assertThat(quality.weightage()).isEqualByComparingTo("40.00");

        // The response must not leak the employee's account details.
        String body = mockMvc.perform(authed(get("/api/manager/assessments/{id}", assignmentId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("evalora.local").doesNotContain("passwordHash");
    }

    // ----------------------------------------------------------------- draft

    @Test
    void savesAndRetrievesADraft() throws Exception {
        ManagerAssessmentResponse saved = saveDraft(
                entry(firstEmployeeKpiId, 4, "  Strong delivery.  "),
                entry(secondEmployeeKpiId, 5, "Excellent quality."));

        assertThat(saved.editable()).isTrue();
        assertThat(managerRatingOf(saved, firstEmployeeKpiId)).isEqualTo(4);
        assertThat(managerCommentOf(saved, firstEmployeeKpiId)).isEqualTo("Strong delivery.");
        assertThat(managerRatingOf(saved, secondEmployeeKpiId)).isEqualTo(5);

        ManagerAssessmentResponse reloaded = getDetail(assignmentId);
        assertThat(managerRatingOf(reloaded, firstEmployeeKpiId)).isEqualTo(4);
        assertThat(managerCommentOf(reloaded, firstEmployeeKpiId)).isEqualTo("Strong delivery.");

        // Self-assessment is still exactly what the employee submitted.
        assertThat(selfRatingOf(reloaded, firstEmployeeKpiId)).isEqualTo(4);
        assertThat(selfCommentOf(reloaded, firstEmployeeKpiId)).isEqualTo("Delivered on time.");

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.MANAGER_REVIEW_PENDING);
        assertThat(assignment.getManagerSubmittedAt()).isNull();
    }

    @Test
    void savesAPartialDraft() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 3, null));

        ManagerAssessmentStatusResponse status = getStatus(assignmentId);
        assertThat(status.totalKpis()).isEqualTo(2);
        assertThat(status.ratedKpis()).isEqualTo(1);
        assertThat(status.submitted()).isFalse();
        assertThat(status.managerSubmittedAt()).isNull();
    }

        @Test
        void partialDraftPreservesPreviouslySavedValuesForOmittedKpis() throws Exception {
                saveDraft(entry(firstEmployeeKpiId, 4, "First."), entry(secondEmployeeKpiId, 5, "Second."));
                saveDraft(entry(firstEmployeeKpiId, 3, "Updated first."));

                ManagerAssessmentResponse detail = getDetail(assignmentId);
                assertThat(managerRatingOf(detail, firstEmployeeKpiId)).isEqualTo(3);
                assertThat(managerCommentOf(detail, firstEmployeeKpiId)).isEqualTo("Updated first.");
                assertThat(managerRatingOf(detail, secondEmployeeKpiId)).isEqualTo(5);
                assertThat(managerCommentOf(detail, secondEmployeeKpiId)).isEqualTo("Second.");
        }

    @Test
    void emptyDraftIsAccepted() throws Exception {
        mockMvc.perform(authed(put("/api/manager/assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kpis", List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(true));

        assertThat(managerAssessmentRepository.count()).isZero();
    }

    @Test
    void repeatedDraftSavesUpdateTheSameRows() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 2, "First pass."),
                entry(secondEmployeeKpiId, 3, "First pass."));
        saveDraft(entry(firstEmployeeKpiId, 5, "Second pass."),
                entry(secondEmployeeKpiId, 4, "Second pass."));

        assertThat(managerAssessmentRepository.count()).isEqualTo(2);
        ManagerAssessmentResponse detail = getDetail(assignmentId);
        assertThat(managerRatingOf(detail, firstEmployeeKpiId)).isEqualTo(5);
        assertThat(managerCommentOf(detail, firstEmployeeKpiId)).isEqualTo("Second pass.");
        assertThat(managerRatingOf(detail, secondEmployeeKpiId)).isEqualTo(4);
    }

    @Test
    void draftRejectsRatingsOutsideOneToFive() throws Exception {
        expectBadRequest(List.of(entry(firstEmployeeKpiId, 0, null)), INVALID_RATING_MESSAGE);
        expectBadRequest(List.of(entry(firstEmployeeKpiId, 6, null)), INVALID_RATING_MESSAGE);
        assertThat(managerAssessmentRepository.count()).isZero();
    }

    @Test
    void draftRejectsDecimalRating() throws Exception {
        expectBadRequest(List.of(entry(firstEmployeeKpiId, 3.5, null)), INVALID_RATING_MESSAGE);
        assertThat(managerAssessmentRepository.count()).isZero();
    }

    @Test
    void draftRejectsDuplicateKpiIds() throws Exception {
        expectBadRequest(List.of(entry(firstEmployeeKpiId, 3, null), entry(firstEmployeeKpiId, 4, null)),
                "The same KPI cannot be rated twice in one request.");
        assertThat(managerAssessmentRepository.count()).isZero();
    }

    @Test
    void draftRejectsKpiFromAnotherAssignment() throws Exception {
        expectBadRequest(List.of(entry(otherEmployeeKpiId, 3, null)),
                "The selected KPI does not belong to this assessment.");
        assertThat(managerAssessmentRepository.count()).isZero();
    }

    @Test
    void draftRejectsMissingEntries() throws Exception {
        mockMvc.perform(authed(put("/api/manager/assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Manager assessment entries are required."));
    }

    @Test
    void invalidDraftDoesNotPartiallyModifySavedData() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 4, "Keep me."));

        mockMvc.perform(authed(put("/api/manager/assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draft(entry(firstEmployeeKpiId, 5, "Should not stick."),
                                entry(secondEmployeeKpiId, 9, null)))))
                .andExpect(status().isBadRequest());

        ManagerAssessmentResponse detail = getDetail(assignmentId);
        assertThat(managerRatingOf(detail, firstEmployeeKpiId)).isEqualTo(4);
        assertThat(managerCommentOf(detail, firstEmployeeKpiId)).isEqualTo("Keep me.");
        assertThat(managerRatingOf(detail, secondEmployeeKpiId)).isNull();
    }

    @Test
    void draftIgnoresClientSuppliedOwnershipIds() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kpis", List.of(entry(firstEmployeeKpiId, 4, "Mine.")));
        body.put("managerId", otherManager.getId());
        body.put("employeeId", otherEmployee.getId());
        body.put("assignmentId", otherAssignmentId);

        mockMvc.perform(authed(put("/api/manager/assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk());

        List<ManagerAssessment> rows = managerAssessmentRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getManager().getId()).isEqualTo(manager.getId());
        assertThat(rows.get(0).getEmployeeKpi().getId()).isEqualTo(firstEmployeeKpiId);
    }

    // --------------------------------------------------------------- submission

    @Test
    void submissionRequiresEveryKpiToBeRated() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 4, null));

        mockMvc.perform(authed(post("/api/manager/assessments/{id}/submit", assignmentId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Every assigned KPI must have a manager rating from 1 to 5 before submitting."));

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.MANAGER_REVIEW_PENDING);
        assertThat(assignment.getManagerSubmittedAt()).isNull();
    }

    @Test
    void submissionMovesTheAssignmentToHrReview() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 4, "Delivered."), entry(secondEmployeeKpiId, 5, "Solid."));
        java.time.OffsetDateTime selfSubmittedAt = assignmentRepository.findById(assignmentId)
                .orElseThrow().getSelfSubmittedAt();

        ManagerAssessmentStatusResponse submitted = submit();
        assertThat(submitted.submitted()).isTrue();
        assertThat(submitted.assignmentStatus()).isEqualTo("HR_REVIEW_PENDING");
        assertThat(submitted.managerSubmittedAt()).isNotNull();
        assertThat(submitted.ratedKpis()).isEqualTo(2);

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.HR_REVIEW_PENDING);
        assertThat(assignment.getManagerSubmittedAt()).isNotNull();
        assertThat(assignment.getHrFinalizedAt()).isNull();
        assertThat(assignment.getSelfSubmittedAt()).isEqualTo(selfSubmittedAt);
        assertThat(selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId)).hasSize(2);
        assertThat(managerAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId)).hasSize(2);
        assertThat(hrAssessmentRepository.count()).isZero();
        assertThat(performanceResultRepository.count()).isZero();

        // Only the assignment moves; the cycle stays published.
        assertThat(cycleRepository.findById(cycleId).orElseThrow().getStatus())
                .isEqualTo(CycleStatus.PUBLISHED);
    }

    @Test
    void submissionTwiceIsRejected() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));
        submit();

        mockMvc.perform(authed(post("/api/manager/assessments/{id}/submit", assignmentId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("The manager assessment has already been submitted."));
    }

    @Test
    void draftCannotBeChangedAfterSubmission() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 4, "Manager one."),
                entry(secondEmployeeKpiId, 5, "Manager two."));
        submit();

        mockMvc.perform(authed(put("/api/manager/assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draft(entry(firstEmployeeKpiId, 1, "Too late")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("The manager assessment has already been submitted."));

        ManagerAssessmentResponse detail = getDetail(assignmentId);
        assertThat(detail.editable()).isFalse();
        assertThat(detail.assignmentStatus()).isEqualTo("HR_REVIEW_PENDING");
        assertThat(managerRatingOf(detail, firstEmployeeKpiId)).isEqualTo(4);
        assertThat(managerCommentOf(detail, firstEmployeeKpiId)).isEqualTo("Manager one.");
        assertThat(managerRatingOf(detail, secondEmployeeKpiId)).isEqualTo(5);
        assertThat(managerCommentOf(detail, secondEmployeeKpiId)).isEqualTo("Manager two.");
    }

    @Test
    void statusReflectsDraftProgress() throws Exception {
        ManagerAssessmentStatusResponse initial = getStatus(assignmentId);
        assertThat(initial.totalKpis()).isEqualTo(2);
        assertThat(initial.ratedKpis()).isZero();
        assertThat(initial.submitted()).isFalse();

        saveDraft(entry(firstEmployeeKpiId, 3, null));
        assertThat(getStatus(assignmentId).ratedKpis()).isEqualTo(1);

        saveDraft(entry(secondEmployeeKpiId, 4, null));
        assertThat(getStatus(assignmentId).ratedKpis()).isEqualTo(2);

        submit();
        ManagerAssessmentStatusResponse afterSubmit = getStatus(assignmentId);
        assertThat(afterSubmit.submitted()).isTrue();
        assertThat(afterSubmit.assignmentStatus()).isEqualTo("HR_REVIEW_PENDING");
    }

    // ----------------------------------------------------------------- helpers

    private ManagerAssessmentResponse getDetail(Long id) throws Exception {
        MvcResult result = mockMvc.perform(authed(get("/api/manager/assessments/{id}", id)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                ManagerAssessmentResponse.class);
    }

    private ManagerAssessmentStatusResponse getStatus(Long id) throws Exception {
        MvcResult result = mockMvc.perform(authed(get("/api/manager/assessments/{id}/status", id)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                ManagerAssessmentStatusResponse.class);
    }

    private List<ManagerQueueItemResponse> listQueue(String token) throws Exception {
        MvcResult result = mockMvc.perform(withToken(get("/api/manager/assessments"), token))
                .andExpect(status().isOk())
                .andReturn();
        return List.of(objectMapper.readValue(result.getResponse().getContentAsString(),
                ManagerQueueItemResponse[].class));
    }

    private ManagerAssessmentResponse saveDraft(Map<String, Object>... entries) throws Exception {
        return saveDraft(List.of(entries));
    }

    private ManagerAssessmentResponse saveDraft(List<Map<String, Object>> entries) throws Exception {
        MvcResult result = mockMvc.perform(authed(put("/api/manager/assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kpis", entries))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                ManagerAssessmentResponse.class);
    }

    private ManagerAssessmentStatusResponse submit() throws Exception {
        MvcResult result = mockMvc.perform(authed(post("/api/manager/assessments/{id}/submit", assignmentId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                ManagerAssessmentStatusResponse.class);
    }

    /** Asserts 400 plus the exact validation message. */
    private void expectBadRequest(List<Map<String, Object>> entries, String message) throws Exception {
        mockMvc.perform(authed(put("/api/manager/assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kpis", entries))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(message));
    }

    private static final String INVALID_RATING_MESSAGE =
            "Manager assessment rating must be a whole number between 1 and 5.";

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

    private static Map<String, Object> draft(List<Map<String, Object>> entries) {
        return Map.of("kpis", entries);
    }

    private static Integer managerRatingOf(ManagerAssessmentResponse response, Long employeeKpiId) {
        return response.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(employeeKpiId))
                .findFirst().orElseThrow().managerRating();
    }

    private static String managerCommentOf(ManagerAssessmentResponse response, Long employeeKpiId) {
        return response.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(employeeKpiId))
                .findFirst().orElseThrow().managerComment();
    }

    private static Integer selfRatingOf(ManagerAssessmentResponse response, Long employeeKpiId) {
        return response.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(employeeKpiId))
                .findFirst().orElseThrow().selfRating();
    }

    private static String selfCommentOf(ManagerAssessmentResponse response, Long employeeKpiId) {
        return response.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(employeeKpiId))
                .findFirst().orElseThrow().selfComment();
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
        return withToken(builder, managerToken);
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder builder, String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
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
