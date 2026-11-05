package com.evalora.backend.controller;

import com.evalora.backend.dto.ConfigureCycleEmployeesRequest;
import com.evalora.backend.dto.ConfigureCycleKpisRequest;
import com.evalora.backend.dto.CycleEmployeeResponse;
import com.evalora.backend.dto.CycleEmployeeSelectionRequest;
import com.evalora.backend.dto.CycleKpiSelectionRequest;
import com.evalora.backend.dto.EmployeeCycleResponse;
import com.evalora.backend.dto.SelfAssessmentKpiResponse;
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
import com.evalora.backend.repository.CycleKpiRepository;
import com.evalora.backend.repository.EmployeeAssignmentRepository;
import com.evalora.backend.repository.EmployeeKpiRepository;
import com.evalora.backend.repository.EmployeeManagerAssignmentRepository;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.KpiMasterRepository;
import com.evalora.backend.repository.PerformanceCycleRepository;
import com.evalora.backend.repository.SelfAssessmentRepository;
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
 * End-to-end employee self-assessment through the real filter chain,
 * controller, service and H2 in PostgreSQL mode.
 *
 * <p>H2 proves the JPA behaviour and the HTTP contract. It does not prove
 * PostgreSQL-specific index or row-lock behaviour; those remain manual
 * PostgreSQL checks.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_self_assessment;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000"
})
class EmployeeSelfAssessmentIntegrationTest {

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
    private KpiMasterRepository kpiRepository;

    @Autowired
    private PerformanceCycleService cycleService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private User hrUser;
    private Employee manager;
    private Employee employee;
    private Employee otherEmployee;
    private KpiMaster deliveryMaster;
    private KpiMaster qualityMaster;

    private String hrToken;
    private String managerToken;
    private String employeeToken;
    private String otherEmployeeToken;

    private Long cycleId;
    private Long assignmentId;
    private Long otherAssignmentId;
    private Long firstEmployeeKpiId;
    private Long secondEmployeeKpiId;
    private Long otherEmployeeKpiId;
    private Long draftAssignmentId;

    @BeforeEach
    void setUp() {
        clearDatabase();

        hrUser = userRepository.saveAndFlush(CycleTestData.user("hr.self@evalora.local", Role.HR));
        manager = profile(Role.MANAGER, "MGR-SELF", "Mona Manager");
        employee = profile(Role.EMPLOYEE, "EMP-SELF", "Eve Employee");
        otherEmployee = profile(Role.EMPLOYEE, "EMP-SELF-2", "Oscar Other");

        deliveryMaster = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Delivery Quality", "Meets agreed deadlines.", "Delivery"));
        qualityMaster = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Quality", "Work meets the quality bar.", "Quality"));

        hrToken = jwtService.generateToken(hrUser);
        managerToken = jwtService.generateToken(manager.getUser());
        employeeToken = jwtService.generateToken(employee.getUser());
        otherEmployeeToken = jwtService.generateToken(otherEmployee.getUser());

        cycleId = cycleService.create(CycleTestData.request("Annual 2026"), hrUser.getId()).id();
        cycleService.configureKpis(cycleId, new ConfigureCycleKpisRequest(List.of(
                new CycleKpiSelectionRequest(deliveryMaster.getId(), new BigDecimal("60.00")),
                new CycleKpiSelectionRequest(qualityMaster.getId(), new BigDecimal("40.00")))));
        cycleService.configureEmployees(cycleId, new ConfigureCycleEmployeesRequest(List.of(
                new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()),
                new CycleEmployeeSelectionRequest(otherEmployee.getId(), manager.getId()))));
        cycleService.publish(cycleId);

        EmployeeAssignment own = assignmentFor(cycleId, employee.getId());
        EmployeeAssignment other = assignmentFor(cycleId, otherEmployee.getId());
        assignmentId = own.getId();
        otherAssignmentId = other.getId();

        List<EmployeeKpi> ownKpis = employeeKpiRepository.findByAssignment_IdOrderByIdAsc(assignmentId);
        firstEmployeeKpiId = ownKpis.get(0).getId();
        secondEmployeeKpiId = ownKpis.get(1).getId();
        otherEmployeeKpiId = employeeKpiRepository.findByAssignment_IdOrderByIdAsc(otherAssignmentId).get(0).getId();

        // A draft cycle with a Phase 3D roster row that must stay invisible.
        Long draftCycleId = cycleService.create(CycleTestData.request("Draft 2026"), hrUser.getId()).id();
        cycleService.configureKpis(draftCycleId, new ConfigureCycleKpisRequest(List.of(
                new CycleKpiSelectionRequest(deliveryMaster.getId(), null))));
        List<CycleEmployeeResponse> draftRoster = cycleService.configureEmployees(draftCycleId,
                new ConfigureCycleEmployeesRequest(List.of(
                        new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()))));
        draftAssignmentId = draftRoster.get(0).id();
    }

    // --------------------------------------------------------------- security

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/employee/self-assessments/cycles"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/employee/self-assessments/{id}", assignmentId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/employee/self-assessments/{id}/status", assignmentId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/employee/self-assessments/{id}/draft", assignmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draft(entry(firstEmployeeKpiId, 3, "ok")))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/employee/self-assessments/{id}/submit", assignmentId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void hrAndManagersCannotUseSelfAssessmentEndpoints() throws Exception {
        for (String token : List.of(hrToken, managerToken)) {
            mockMvc.perform(withToken(get("/api/employee/self-assessments/cycles"), token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withToken(get("/api/employee/self-assessments/{id}", assignmentId), token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withToken(get("/api/employee/self-assessments/{id}/status", assignmentId), token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withToken(put("/api/employee/self-assessments/{id}/draft", assignmentId), token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(draft(entry(firstEmployeeKpiId, 3, "ok")))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withToken(post("/api/employee/self-assessments/{id}/submit", assignmentId), token))
                    .andExpect(status().isForbidden());
        }
        assertThat(selfAssessmentRepository.count()).isZero();
    }

    @Test
    void assignmentIdsCannotBeSwappedToReadAnotherEmployeesData() throws Exception {
        String token = otherEmployeeToken;

        for (String path : List.of("/api/employee/self-assessments/{id}",
                "/api/employee/self-assessments/{id}/status")) {
            mockMvc.perform(withToken(get(path, assignmentId), token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Self-assessment not found."));
        }

        mockMvc.perform(withToken(put("/api/employee/self-assessments/{id}/draft", assignmentId), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draft(entry(firstEmployeeKpiId, 5, "Not mine")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Self-assessment not found."));

        mockMvc.perform(withToken(post("/api/employee/self-assessments/{id}/submit", assignmentId), token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Self-assessment not found."));

        assertThat(selfAssessmentRepository.count()).isZero();
    }

    @Test
    void draftCycleAssignmentsAreHidden() throws Exception {
        mockMvc.perform(authed(get("/api/employee/self-assessments/{id}", draftAssignmentId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Self-assessment not found."));

        mockMvc.perform(authed(get("/api/employee/self-assessments/{id}/status", draftAssignmentId)))
                .andExpect(status().isNotFound());

        mockMvc.perform(authed(put("/api/employee/self-assessments/{id}/draft", draftAssignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draft(entry(firstEmployeeKpiId, 3, "Draft cycle")))))
                .andExpect(status().isNotFound());

        mockMvc.perform(authed(post("/api/employee/self-assessments/{id}/submit", draftAssignmentId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownAssignmentReturnsNotFound() throws Exception {
        mockMvc.perform(authed(get("/api/employee/self-assessments/{id}", 999_999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Self-assessment not found."));
        mockMvc.perform(authed(get("/api/employee/self-assessments/{id}/status", 999_999L)))
                .andExpect(status().isNotFound());
    }

    /**
     * Visibility is keyed on exactly {@code PUBLISHED}. A cycle that has moved
     * on (or that a future phase adds) is not an actionable appraisal here and
     * must behave exactly like an unknown assignment on every endpoint.
     */
    @Test
    void onlyPublishedCyclesAreVisible() throws Exception {
        for (CycleStatus status : List.of(CycleStatus.IN_PROGRESS, CycleStatus.COMPLETED)) {
            setCycleStatus(cycleId, status);

            String listing = mockMvc.perform(authed(get("/api/employee/self-assessments/cycles")))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(listing).doesNotContain("Annual 2026");

            mockMvc.perform(authed(get("/api/employee/self-assessments/{id}", assignmentId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Self-assessment not found."));
            mockMvc.perform(authed(get("/api/employee/self-assessments/{id}/status", assignmentId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Self-assessment not found."));
            mockMvc.perform(authed(put("/api/employee/self-assessments/{id}/draft", assignmentId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(draft(entry(firstEmployeeKpiId, 3, "Not visible")))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Self-assessment not found."));
            mockMvc.perform(authed(post("/api/employee/self-assessments/{id}/submit", assignmentId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Self-assessment not found."));

            assertThat(selfAssessmentRepository.count()).isZero();
            assertThat(assignmentRepository.findById(assignmentId).orElseThrow().getStatus())
                    .isEqualTo(AssignmentStatus.SELF_ASSESSMENT_PENDING);
            assertThat(assignmentRepository.findById(assignmentId).orElseThrow().getSelfSubmittedAt())
                    .isNull();
        }
    }

    // ---------------------------------------------------------------- listing

    @Test
    void employeeSeesOwnPublishedCycles() throws Exception {
        MvcResult result = mockMvc.perform(authed(get("/api/employee/self-assessments/cycles")))
                .andExpect(status().isOk())
                .andReturn();

        List<EmployeeCycleResponse> cycles = List.of(objectMapper.readValue(
                result.getResponse().getContentAsString(), EmployeeCycleResponse[].class));

        assertThat(cycles).hasSize(1);
        EmployeeCycleResponse listed = cycles.get(0);
        assertThat(listed.assignmentId()).isEqualTo(assignmentId);
        assertThat(listed.cycleId()).isEqualTo(cycleId);
        assertThat(listed.cycleName()).isEqualTo("Annual 2026");
        assertThat(listed.cycleStatus()).isEqualTo("PUBLISHED");
        assertThat(listed.assignmentStatus()).isEqualTo("SELF_ASSESSMENT_PENDING");
        assertThat(listed.publishedAt()).isNotNull();
        assertThat(listed.selfSubmittedAt()).isNull();
        assertThat(listed.selfAssessmentDeadline()).isEqualTo(java.time.LocalDate.of(2026, 11, 30));
    }

    @Test
    void draftCyclesAreNotListed() throws Exception {
        MvcResult result = mockMvc.perform(authed(get("/api/employee/self-assessments/cycles")))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("Annual 2026").doesNotContain("Draft 2026");
    }

    @Test
    void listingIsScopedToTheAuthenticatedEmployee() throws Exception {
        MvcResult result = mockMvc.perform(withToken(get("/api/employee/self-assessments/cycles"), otherEmployeeToken))
                .andExpect(status().isOk())
                .andReturn();

        List<EmployeeCycleResponse> cycles = List.of(objectMapper.readValue(
                result.getResponse().getContentAsString(), EmployeeCycleResponse[].class));

        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0).assignmentId()).isEqualTo(otherAssignmentId);
        assertThat(cycles.get(0).assignmentId()).isNotEqualTo(assignmentId);
    }

    // ----------------------------------------------------------------- detail

    @Test
    void detailReturnsFrozenKpisAndAnEditableDraft() throws Exception {
        SelfAssessmentResponse detail = getDetail(employeeToken, assignmentId);

        assertThat(detail.assignmentId()).isEqualTo(assignmentId);
        assertThat(detail.cycleId()).isEqualTo(cycleId);
        assertThat(detail.cycleStatus()).isEqualTo("PUBLISHED");
        assertThat(detail.assignmentStatus()).isEqualTo("SELF_ASSESSMENT_PENDING");
        assertThat(detail.editable()).isTrue();
        assertThat(detail.selfSubmittedAt()).isNull();
        assertThat(detail.kpis()).hasSize(2);

        SelfAssessmentKpiResponse delivery = detail.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(firstEmployeeKpiId))
                .findFirst().orElseThrow();
        assertThat(delivery.kpiId()).isEqualTo(deliveryMaster.getId());
        assertThat(delivery.name()).isEqualTo("Delivery Quality");
        assertThat(delivery.description()).isEqualTo("Meets agreed deadlines.");
        assertThat(delivery.category()).isEqualTo("Delivery");
        assertThat(delivery.weightage()).isEqualByComparingTo("60.00");
        assertThat(delivery.rating()).isNull();
        assertThat(delivery.comment()).isNull();

        SelfAssessmentKpiResponse quality = detail.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(secondEmployeeKpiId))
                .findFirst().orElseThrow();
        assertThat(quality.name()).isEqualTo("Quality");
        assertThat(quality.weightage()).isEqualByComparingTo("40.00");
    }

    @Test
    void detailAfterSubmissionIsNotEditable() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 4, "first"), entry(secondEmployeeKpiId, 5, "second"));
        submit();

        SelfAssessmentResponse detail = getDetail(employeeToken, assignmentId);
        assertThat(detail.editable()).isFalse();
        assertThat(detail.assignmentStatus()).isEqualTo("MANAGER_REVIEW_PENDING");
        assertThat(detail.selfSubmittedAt()).isNotNull();
    }

    // ------------------------------------------------------------------ draft

    @Test
    void savesAndRetrievesADraft() throws Exception {
        SelfAssessmentResponse saved = saveDraft(
                entry(firstEmployeeKpiId, 4, "  Delivered on time.  "),
                entry(secondEmployeeKpiId, 5, "Excellent quality."));

        assertThat(saved.editable()).isTrue();
        assertThat(ratingOf(saved, firstEmployeeKpiId)).isEqualTo(4);
        assertThat(commentOf(saved, firstEmployeeKpiId)).isEqualTo("Delivered on time.");
        assertThat(ratingOf(saved, secondEmployeeKpiId)).isEqualTo(5);

        SelfAssessmentResponse reloaded = getDetail(employeeToken, assignmentId);
        assertThat(ratingOf(reloaded, firstEmployeeKpiId)).isEqualTo(4);
        assertThat(ratingOf(reloaded, secondEmployeeKpiId)).isEqualTo(5);

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.SELF_ASSESSMENT_PENDING);
        assertThat(assignment.getSelfSubmittedAt()).isNull();
    }

    @Test
    void savesAPartiallyCompletedDraft() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 3, null));

        SelfAssessmentStatusResponse status = getStatus(assignmentId);
        assertThat(status.totalKpis()).isEqualTo(2);
        assertThat(status.ratedKpis()).isEqualTo(1);
        assertThat(status.submitted()).isFalse();
    }

    @Test
    void emptyDraftIsAccepted() throws Exception {
        mockMvc.perform(authed(put("/api/employee/self-assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kpis", List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(true));

        assertThat(selfAssessmentRepository.count()).isZero();
    }

    @Test
    void repeatedDraftSavesUpdateTheSameRows() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 2, "First pass."),
                entry(secondEmployeeKpiId, 3, "First pass."));
        saveDraft(entry(firstEmployeeKpiId, 5, "Second pass."),
                entry(secondEmployeeKpiId, 4, "Second pass."));

        assertThat(selfAssessmentRepository.count()).isEqualTo(2);
        SelfAssessmentResponse detail = getDetail(employeeToken, assignmentId);
        assertThat(ratingOf(detail, firstEmployeeKpiId)).isEqualTo(5);
        assertThat(commentOf(detail, firstEmployeeKpiId)).isEqualTo("Second pass.");
        assertThat(ratingOf(detail, secondEmployeeKpiId)).isEqualTo(4);
    }

    @Test
    void draftRejectsRatingBelowOne() throws Exception {
        expectBadRequest(entry(firstEmployeeKpiId, 0, null),
                "Self-assessment rating must be a whole number between 1 and 5.");
    }

    @Test
    void draftRejectsRatingAboveFive() throws Exception {
        expectBadRequest(entry(firstEmployeeKpiId, 6, null),
                "Self-assessment rating must be a whole number between 1 and 5.");
    }

    @Test
    void draftRejectsDecimalRating() throws Exception {
        expectBadRequest(entry(firstEmployeeKpiId, 3.5, null),
                "Self-assessment rating must be a whole number between 1 and 5.");
    }

    @Test
    void draftRejectsDuplicateKpiIds() throws Exception {
        expectBadRequest(List.of(entry(firstEmployeeKpiId, 3, null), entry(firstEmployeeKpiId, 4, null)),
                "The same KPI cannot be rated twice in one request.");
    }

    @Test
    void draftRejectsKpiFromAnotherAssignment() throws Exception {
        expectBadRequest(entry(otherEmployeeKpiId, 3, null),
                "The selected KPI does not belong to this self-assessment.");
    }

    @Test
    void draftRejectsMissingEntries() throws Exception {
        mockMvc.perform(authed(put("/api/employee/self-assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Self-assessment entries are required."));
    }

    @Test
    void invalidDraftDoesNotPartiallyModifySavedData() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 4, "Keep me."));

        mockMvc.perform(authed(put("/api/employee/self-assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draft(entry(firstEmployeeKpiId, 5, "Should not stick."),
                                entry(secondEmployeeKpiId, 9, null)))))
                .andExpect(status().isBadRequest());

        SelfAssessmentResponse detail = getDetail(employeeToken, assignmentId);
        assertThat(ratingOf(detail, firstEmployeeKpiId)).isEqualTo(4);
        assertThat(commentOf(detail, firstEmployeeKpiId)).isEqualTo("Keep me.");
        assertThat(ratingOf(detail, secondEmployeeKpiId)).isNull();
    }

    // --------------------------------------------------------------- submission

    @Test
    void submissionRequiresEveryKpiToBeRated() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 4, null));

        mockMvc.perform(authed(post("/api/employee/self-assessments/{id}/submit", assignmentId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Every KPI must be rated from 1 to 5 before submitting."));

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.SELF_ASSESSMENT_PENDING);
        assertThat(assignment.getSelfSubmittedAt()).isNull();
    }

    @Test
    void submissionMovesTheAssignmentToManagerReview() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 4, "Delivered."), entry(secondEmployeeKpiId, 5, "Solid."));

        SelfAssessmentStatusResponse submitted = submit();
        assertThat(submitted.submitted()).isTrue();
        assertThat(submitted.assignmentStatus()).isEqualTo("MANAGER_REVIEW_PENDING");
        assertThat(submitted.selfSubmittedAt()).isNotNull();
        assertThat(submitted.ratedKpis()).isEqualTo(2);

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.MANAGER_REVIEW_PENDING);
        assertThat(assignment.getSelfSubmittedAt()).isNotNull();

        // Only the assignment moves; the cycle is still the published one.
        assertThat(cycleRepository.findById(cycleId).orElseThrow().getStatus().name())
                .isEqualTo("PUBLISHED");
    }

    @Test
    void submissionTwiceIsRejected() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));
        submit();

        mockMvc.perform(authed(post("/api/employee/self-assessments/{id}/submit", assignmentId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The self-assessment has already been submitted."));
    }

    @Test
    void draftCannotBeChangedAfterSubmission() throws Exception {
        saveDraft(entry(firstEmployeeKpiId, 4, null), entry(secondEmployeeKpiId, 5, null));
        submit();

        mockMvc.perform(authed(put("/api/employee/self-assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draft(entry(firstEmployeeKpiId, 1, "Too late")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The self-assessment has already been submitted."));

        SelfAssessmentResponse detail = getDetail(employeeToken, assignmentId);
        assertThat(ratingOf(detail, firstEmployeeKpiId)).isEqualTo(4);
    }

    @Test
    void statusReflectsDraftProgress() throws Exception {
        SelfAssessmentStatusResponse initial = getStatus(assignmentId);
        assertThat(initial.totalKpis()).isEqualTo(2);
        assertThat(initial.ratedKpis()).isZero();
        assertThat(initial.submitted()).isFalse();

        saveDraft(entry(firstEmployeeKpiId, 3, null));
        assertThat(getStatus(assignmentId).ratedKpis()).isEqualTo(1);

        saveDraft(entry(secondEmployeeKpiId, 4, null));
        assertThat(getStatus(assignmentId).ratedKpis()).isEqualTo(2);

        submit();
        SelfAssessmentStatusResponse afterSubmit = getStatus(assignmentId);
        assertThat(afterSubmit.submitted()).isTrue();
        assertThat(afterSubmit.assignmentStatus()).isEqualTo("MANAGER_REVIEW_PENDING");
    }

    // ----------------------------------------------------------------- helpers

    private SelfAssessmentResponse getDetail(String token, Long id) throws Exception {
        MvcResult result = mockMvc.perform(withToken(get("/api/employee/self-assessments/{id}", id), token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), SelfAssessmentResponse.class);
    }

    private SelfAssessmentStatusResponse getStatus(Long id) throws Exception {
        MvcResult result = mockMvc.perform(authed(get("/api/employee/self-assessments/{id}/status", id)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                SelfAssessmentStatusResponse.class);
    }

    private SelfAssessmentResponse saveDraft(Map<String, Object>... entries) throws Exception {
        return saveDraft(List.of(entries));
    }

    private SelfAssessmentResponse saveDraft(List<Map<String, Object>> entries) throws Exception {
        MvcResult result = mockMvc.perform(authed(put("/api/employee/self-assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kpis", entries))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), SelfAssessmentResponse.class);
    }

    private SelfAssessmentStatusResponse submit() throws Exception {
        MvcResult result = mockMvc.perform(authed(post("/api/employee/self-assessments/{id}/submit", assignmentId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                SelfAssessmentStatusResponse.class);
    }

    private void expectBadRequest(Map<String, Object> entry, String message) throws Exception {
        expectBadRequest(List.of(entry), message);
    }

    private void expectBadRequest(List<Map<String, Object>> entries, String message) throws Exception {
        mockMvc.perform(authed(put("/api/employee/self-assessments/{id}/draft", assignmentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kpis", entries))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(message));
    }

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

    private static Integer ratingOf(SelfAssessmentResponse response, Long employeeKpiId) {
        return response.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(employeeKpiId))
                .findFirst().orElseThrow().rating();
    }

    private static String commentOf(SelfAssessmentResponse response, Long employeeKpiId) {
        return response.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(employeeKpiId))
                .findFirst().orElseThrow().comment();
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
        return withToken(builder, employeeToken);
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder builder, String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
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
