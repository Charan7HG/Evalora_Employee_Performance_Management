package com.evalora.backend.controller;

import com.evalora.backend.dto.CycleRequest;
import com.evalora.backend.dto.CycleResponse;
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
import com.evalora.backend.security.JwtService;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HR cycle management through the real filter chain, controller,
 * service and H2 in PostgreSQL mode.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_cycle;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
class HrCycleManagementIntegrationTest {

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
    private KpiMasterRepository kpiRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private User hrUser;
    private String hrToken;
    private String managerToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        clearDatabase();

        hrUser = userRepository.saveAndFlush(CycleTestData.user("hr.cycle@evalora.local", Role.HR));
        User managerUser = userRepository.saveAndFlush(
                CycleTestData.user("manager.cycle@evalora.local", Role.MANAGER));
        User employeeUser = userRepository.saveAndFlush(
                CycleTestData.user("employee.cycle@evalora.local", Role.EMPLOYEE));

        hrToken = jwtService.generateToken(hrUser);
        managerToken = jwtService.generateToken(managerUser);
        employeeToken = jwtService.generateToken(employeeUser);
    }

    // ------------------------------------------------------------------ create

    @Test
    void hrCanCreateACycle() throws Exception {
        CycleResponse created = create(CycleTestData.request("Annual 2026"));

        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("Annual 2026");
        assertThat(created.description()).isEqualTo("Annual performance review.");
        assertThat(created.startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(created.endDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(created.selfAssessmentDeadline()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(created.managerReviewDeadline()).isEqualTo(LocalDate.of(2026, 12, 15));
        assertThat(created.hrReviewDeadline()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.createdById()).isEqualTo(hrUser.getId());
        assertThat(created.publishedAt()).isNull();
        assertThat(created.completedAt()).isNull();
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.updatedAt()).isNotNull();
    }

    @Test
    void creationTrimsTheNameAndDescription() throws Exception {
        CycleResponse created = create(new CycleRequest("  Annual 2026  ", "   ",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 15),
                LocalDate.of(2026, 12, 31)));

        assertThat(created.name()).isEqualTo("Annual 2026");
        assertThat(created.description()).isNull();
    }

    @Test
    void blankNameIsRejected() throws Exception {
        mockMvc.perform(authed(post("/api/hr/cycles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body("   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cycle name is required."));
    }

    @Test
    void nameLongerThanTheColumnIsRejected() throws Exception {
        mockMvc.perform(authed(post("/api/hr/cycles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(CycleTestData.request("a".repeat(151)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cycle name must be at most 150 characters."));
    }

    @Test
    void missingDatesAreRejected() throws Exception {
        mockMvc.perform(authed(post("/api/hr/cycles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CycleRequest("Missing Dates", null, null, null, null, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.endsWith("is required.")));
    }

    @Test
    void startMustBeBeforeTheEndDate() throws Exception {
        CycleRequest request = CycleTestData.request("Backwards",
                LocalDate.of(2026, 12, 31), LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 15),
                LocalDate.of(2026, 12, 31));

        mockMvc.perform(authed(post("/api/hr/cycles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Start date must be before the end date."));
    }

    @Test
    void selfAssessmentDeadlineMustBeInsideTheCycle() throws Exception {
        CycleRequest request = CycleTestData.request("Outside",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2),
                LocalDate.of(2027, 1, 3));

        mockMvc.perform(authed(post("/api/hr/cycles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Self-assessment deadline must be within the cycle dates."));
    }

    @Test
    void managerDeadlineMustNotPrecedeTheSelfDeadline() throws Exception {
        CycleRequest request = CycleTestData.request("Manager First",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                LocalDate.of(2026, 11, 30), LocalDate.of(2026, 11, 29),
                LocalDate.of(2026, 12, 31));

        mockMvc.perform(authed(post("/api/hr/cycles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Manager review deadline must be on or after the self-assessment deadline."));
    }

    @Test
    void hrDeadlineMustNotPrecedeTheManagerDeadline() throws Exception {
        CycleRequest request = CycleTestData.request("HR First",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 15),
                LocalDate.of(2026, 12, 14));

        mockMvc.perform(authed(post("/api/hr/cycles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("HR review deadline must be on or after the manager review deadline."));
    }

    @Test
    void duplicateNameIgnoringCaseIsRejected() throws Exception {
        create(CycleTestData.request("Annual 2026"));

        mockMvc.perform(authed(post("/api/hr/cycles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(CycleTestData.request("annual 2026"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A cycle with this name already exists."));

        assertThat(cycleRepository.count()).isEqualTo(1);
    }

    @Test
    void protectedFieldsCannotBeSuppliedByTheClient() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Client Controlled");
        body.put("startDate", "2026-01-01");
        body.put("endDate", "2026-12-31");
        body.put("selfAssessmentDeadline", "2026-11-30");
        body.put("managerReviewDeadline", "2026-12-15");
        body.put("hrReviewDeadline", "2026-12-31");
        body.put("id", 999_999);
        body.put("status", "COMPLETED");
        body.put("createdBy", 999_999);
        body.put("publishedAt", "2000-01-01T00:00:00Z");
        body.put("completedAt", "2000-01-01T00:00:00Z");
        body.put("createdAt", "2000-01-01T00:00:00Z");

        MvcResult result = mockMvc.perform(authed(post("/api/hr/cycles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.createdById").value(hrUser.getId()))
                .andExpect(jsonPath("$.publishedAt").value(nullValue()))
                .andExpect(jsonPath("$.completedAt").value(nullValue()))
                .andReturn();

        CycleResponse created = read(result);
        assertThat(created.id()).isNotEqualTo(999_999L);
        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.createdById()).isEqualTo(hrUser.getId());
    }

    // -------------------------------------------------------------------- read

    @Test
    void listReturnsEveryCycleNewestFirst() throws Exception {
        create(CycleTestData.request("First Cycle"));
        create(CycleTestData.request("Second Cycle"));
        create(CycleTestData.request("Third Cycle"));

        mockMvc.perform(authed(get("/api/hr/cycles")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("Third Cycle"))
                .andExpect(jsonPath("$[1].name").value("Second Cycle"))
                .andExpect(jsonPath("$[2].name").value("First Cycle"));
    }

    @Test
    void hrCanReadOneCycle() throws Exception {
        CycleResponse created = create(CycleTestData.request("Annual 2026"));

        mockMvc.perform(authed(get("/api/hr/cycles/{id}", created.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.id()))
                .andExpect(jsonPath("$.name").value("Annual 2026"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void unknownCycleReturnsNotFound() throws Exception {
        mockMvc.perform(authed(get("/api/hr/cycles/{id}", 999_999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Performance cycle not found."));

        mockMvc.perform(authed(put("/api/hr/cycles/{id}", 999_999L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(CycleTestData.request("Ghost"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Performance cycle not found."));
    }

    // ------------------------------------------------------------------ update

    @Test
    void hrCanUpdateADraftCycle() throws Exception {
        CycleResponse created = create(CycleTestData.request("Annual 2026"));

        mockMvc.perform(authed(put("/api/hr/cycles/{id}", created.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CycleRequest("Annual 2026 - Revised", "Revised.",
                                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 11, 30),
                                LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 15),
                                LocalDate.of(2026, 11, 30)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Annual 2026 - Revised"))
                .andExpect(jsonPath("$.description").value("Revised."))
                .andExpect(jsonPath("$.startDate").value("2026-02-01"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.createdById").value(hrUser.getId()));
    }

    @Test
    void updateCannotChangeStatusCreatorOrTimestamps() throws Exception {
        CycleResponse created = create(CycleTestData.request("Annual 2026"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Annual 2026");
        body.put("startDate", "2026-01-01");
        body.put("endDate", "2026-12-31");
        body.put("selfAssessmentDeadline", "2026-11-30");
        body.put("managerReviewDeadline", "2026-12-15");
        body.put("hrReviewDeadline", "2026-12-31");
        body.put("id", 999_999);
        body.put("status", "PUBLISHED");
        body.put("createdBy", 999_999);
        body.put("publishedAt", "2000-01-01T00:00:00Z");
        body.put("completedAt", "2000-01-01T00:00:00Z");

        mockMvc.perform(authed(put("/api/hr/cycles/{id}", created.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.id()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.createdById").value(hrUser.getId()))
                .andExpect(jsonPath("$.publishedAt").value(nullValue()))
                .andExpect(jsonPath("$.completedAt").value(nullValue()));
    }

    @Test
    void updateRejectsDuplicateName() throws Exception {
        create(CycleTestData.request("Existing Cycle"));
        CycleResponse other = create(CycleTestData.request("Other Cycle"));

        mockMvc.perform(authed(put("/api/hr/cycles/{id}", other.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(CycleTestData.request("existing cycle"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A cycle with this name already exists."));
    }

    @Test
    void updateMayChangeOnlyTheCaseOfItsOwnName() throws Exception {
        CycleResponse created = create(CycleTestData.request("Annual 2026"));

        mockMvc.perform(authed(put("/api/hr/cycles/{id}", created.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(CycleTestData.request("annual 2026"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("annual 2026"));
    }

    @Test
    void publishedCycleCannotBeUpdated() throws Exception {
        CycleResponse created = create(CycleTestData.request("Published 2026"));
        markStatus(created.id(), CycleStatus.PUBLISHED);

        mockMvc.perform(authed(put("/api/hr/cycles/{id}", created.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(CycleTestData.request("Changed After Publish"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only a DRAFT cycle can be updated."));
    }

    @Test
    void completedCycleCannotBeUpdated() throws Exception {
        CycleResponse created = create(CycleTestData.request("Completed 2026"));
        markStatus(created.id(), CycleStatus.COMPLETED);

        mockMvc.perform(authed(put("/api/hr/cycles/{id}", created.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(CycleTestData.request("Changed After Completion"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only a DRAFT cycle can be updated."));
    }

    // ---------------------------------------------------------------- security

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/hr/cycles"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/hr/cycles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(CycleTestData.request("No Token"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void managersAndEmployeesCannotUseCycleManagement() throws Exception {
        for (String token : List.of(managerToken, employeeToken)) {
            mockMvc.perform(withToken(get("/api/hr/cycles"), token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withToken(post("/api/hr/cycles"), token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(CycleTestData.request("Forbidden"))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withToken(post("/api/hr/cycles/{id}/publish", 1L), token))
                    .andExpect(status().isForbidden());
        }
    }

    // ----------------------------------------------------------------- helpers

    private CycleResponse create(CycleRequest request) throws Exception {
        MvcResult result = mockMvc.perform(authed(post("/api/hr/cycles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result);
    }

    private CycleResponse read(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), CycleResponse.class);
    }

    private void markStatus(Long cycleId, CycleStatus status) {
        PerformanceCycle cycle = cycleRepository.findById(cycleId).orElseThrow();
        cycle.setStatus(status);
        if (status == CycleStatus.PUBLISHED || status == CycleStatus.COMPLETED) {
            cycle.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        if (status == CycleStatus.COMPLETED) {
            cycle.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        cycleRepository.saveAndFlush(cycle);
    }

    private Object body(String name) {
        return new LinkedHashMap<>(Map.of("name", name,
                "startDate", "2026-01-01",
                "endDate", "2026-12-31",
                "selfAssessmentDeadline", "2026-11-30",
                "managerReviewDeadline", "2026-12-15",
                "hrReviewDeadline", "2026-12-31"));
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
