package com.evalora.backend.controller;

import com.evalora.backend.dto.CreateKpiRequest;
import com.evalora.backend.dto.KpiResponse;
import com.evalora.backend.dto.UpdateKpiRequest;
import com.evalora.backend.entity.KpiMaster;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.repository.KpiMasterRepository;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HR KPI management through the real filter chain, controller,
 * service and H2 in PostgreSQL mode.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_kpi;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
class HrKpiManagementIntegrationTest {

    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(12).encode("Kpi@2026");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KpiMasterRepository kpiRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private User hrUser;
    private String hrToken;

    @BeforeEach
    void setUp() {
        kpiRepository.deleteAll();
        userRepository.deleteAll();

        hrUser = userRepository.saveAndFlush(user("hr.kpi@evalora.local", Role.HR));
        hrToken = jwtService.generateToken(hrUser);
    }

    // ------------------------------------------------------------------ create

    @Test
    void hrCanCreateAKpi() throws Exception {
        KpiResponse created = createKpi("Delivery Quality", "Meets agreed deadlines.",
                "Delivery", new BigDecimal("25.00"));

        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("Delivery Quality");
        assertThat(created.description()).isEqualTo("Meets agreed deadlines.");
        assertThat(created.category()).isEqualTo("Delivery");
        assertThat(created.defaultWeightage()).isEqualByComparingTo("25.00");
        assertThat(created.active()).isTrue();
        assertThat(created.createdById()).isEqualTo(hrUser.getId());
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.updatedAt()).isNotNull();

        KpiMaster stored = kpiRepository.findById(created.id()).orElseThrow();
        assertThat(stored.getCreatedBy().getId()).isEqualTo(hrUser.getId());
    }

    @Test
    void creationTrimsTextAndAllowsMissingOptionalFields() throws Exception {
        KpiResponse created = createKpi("  Delivery Quality  ", "   ", " ", null);

        assertThat(created.name()).isEqualTo("Delivery Quality");
        assertThat(created.description()).isNull();
        assertThat(created.category()).isNull();
        assertThat(created.defaultWeightage()).isNull();
    }

    @Test
    void missingNameIsRejected() throws Exception {
        mockMvc.perform(authed(post("/api/hr/kpis"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("KPI name is required."));
    }

    @Test
    void blankNameIsRejected() throws Exception {
        mockMvc.perform(authed(post("/api/hr/kpis"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateKpiRequest("   ", null, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("KPI name is required."));
    }

    @Test
    void nameLongerThanTheColumnIsRejected() throws Exception {
        mockMvc.perform(authed(post("/api/hr/kpis"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateKpiRequest("a".repeat(151), null, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("KPI name must be at most 150 characters."));
    }

    @Test
    void zeroWeightageIsRejected() throws Exception {
        mockMvc.perform(authed(post("/api/hr/kpis"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateKpiRequest("Ownership", null, null, BigDecimal.ZERO))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Default weightage must be greater than 0 and at most 100."));
    }

    @Test
    void weightageAboveOneHundredIsRejected() throws Exception {
        mockMvc.perform(authed(post("/api/hr/kpis"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateKpiRequest("Ownership", null, null, new BigDecimal("100.01")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Default weightage must be greater than 0 and at most 100."));
    }

    @Test
    void weightageWithTooManyDecimalsIsRejected() throws Exception {
        mockMvc.perform(authed(post("/api/hr/kpis"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateKpiRequest("Ownership", null, null, new BigDecimal("12.345")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Default weightage must have at most 2 decimal places."));
    }

    @Test
    void duplicateNameIsRejected() throws Exception {
        createKpi("Delivery Quality", null, null, null);

        mockMvc.perform(authed(post("/api/hr/kpis"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateKpiRequest("Delivery Quality", null, null, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A KPI with this name already exists."));

        assertThat(kpiRepository.count()).isEqualTo(1);
    }

    @Test
    void duplicateNameIgnoringCaseIsRejected() throws Exception {
        createKpi("Delivery Quality", null, null, null);

        mockMvc.perform(authed(post("/api/hr/kpis"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateKpiRequest("delivery quality", null, null, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A KPI with this name already exists."));

        assertThat(kpiRepository.count()).isEqualTo(1);
    }

    @Test
    void creatorAndStateCannotBeSuppliedByTheClient() throws Exception {
        String body = json(Map.of(
                "name", "Client Controlled",
                "id", 12345,
                "active", false,
                "createdBy", 99999,
                "createdAt", "2000-01-01T00:00:00Z"));

        MvcResult result = mockMvc.perform(authed(post("/api/hr/kpis"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdById").value(hrUser.getId()))
                .andReturn();

        KpiResponse created = read(result);
        assertThat(created.id()).isNotEqualTo(12345L);
        assertThat(kpiRepository.count()).isEqualTo(1);
    }

    // -------------------------------------------------------------------- read

    @Test
    void listReturnsEveryKpiNewestFirst() throws Exception {
        createKpi("First KPI", null, null, null);
        createKpi("Second KPI", null, null, null);
        createKpi("Third KPI", null, null, null);

        mockMvc.perform(authed(get("/api/hr/kpis")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("Third KPI"))
                .andExpect(jsonPath("$[1].name").value("Second KPI"))
                .andExpect(jsonPath("$[2].name").value("First KPI"));
    }

    @Test
    void listFiltersByActiveStatus() throws Exception {
        KpiResponse active = createKpi("Active KPI", null, null, null);
        KpiResponse inactive = createKpi("Inactive KPI", null, null, null);
        mockMvc.perform(authed(patch("/api/hr/kpis/{id}/deactivate", inactive.id())))
                .andExpect(status().isOk());

        mockMvc.perform(authed(get("/api/hr/kpis")).param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(active.id()));

        mockMvc.perform(authed(get("/api/hr/kpis")).param("active", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(inactive.id()));

        // No filter still returns both, so HR can manage deactivated KPIs.
        mockMvc.perform(authed(get("/api/hr/kpis")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void invalidActiveFilterIsRejected() throws Exception {
        mockMvc.perform(authed(get("/api/hr/kpis")).param("active", "sometimes"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'active'."));
    }

    @Test
    void hrCanReadOneKpi() throws Exception {
        KpiResponse created = createKpi("Customer Focus", "Listens before acting.",
                "Behaviour", new BigDecimal("15.50"));

        mockMvc.perform(authed(get("/api/hr/kpis/{id}", created.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.id()))
                .andExpect(jsonPath("$.name").value("Customer Focus"))
                .andExpect(jsonPath("$.description").value("Listens before acting."))
                .andExpect(jsonPath("$.category").value("Behaviour"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdById").value(hrUser.getId()));
    }

    @Test
    void unknownKpiReturnsNotFound() throws Exception {
        mockMvc.perform(authed(get("/api/hr/kpis/{id}", 999_999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("KPI not found."));
    }

    // ------------------------------------------------------------------ update

    @Test
    void hrCanUpdateKpiDetails() throws Exception {
        KpiResponse created = createKpi("Delivery Quality", "Old description.",
                "Delivery", new BigDecimal("10.00"));

        mockMvc.perform(authed(put("/api/hr/kpis/{id}", created.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateKpiRequest("Delivery Excellence", "New description.",
                                "Quality", new BigDecimal("30.00")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Delivery Excellence"))
                .andExpect(jsonPath("$.description").value("New description."))
                .andExpect(jsonPath("$.category").value("Quality"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdById").value(hrUser.getId()));

        KpiMaster stored = kpiRepository.findById(created.id()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("Delivery Excellence");
        assertThat(stored.getDefaultWeightage()).isEqualByComparingTo("30.00");
    }

    @Test
    void updateCannotChangeStateOrCreator() throws Exception {
        KpiResponse created = createKpi("State Protected", null, null, null);
        mockMvc.perform(authed(patch("/api/hr/kpis/{id}/deactivate", created.id())))
                .andExpect(status().isOk());

        String body = json(Map.of(
                "name", "State Protected",
                "active", true,
                "createdBy", 99999,
                "id", 12345,
                "createdAt", "2000-01-01T00:00:00Z"));

        mockMvc.perform(authed(put("/api/hr/kpis/{id}", created.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.id()))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.createdById").value(hrUser.getId()));
    }

    @Test
    void updateRejectsInvalidFields() throws Exception {
        KpiResponse created = createKpi("Valid Name", null, null, null);

        mockMvc.perform(authed(put("/api/hr/kpis/{id}", created.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateKpiRequest("   ", null, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("KPI name is required."));

        mockMvc.perform(authed(put("/api/hr/kpis/{id}", created.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateKpiRequest("Valid Name", null, null, new BigDecimal("150")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Default weightage must be greater than 0 and at most 100."));
    }

    @Test
    void updateRejectsDuplicateName() throws Exception {
        createKpi("Existing KPI", null, null, null);
        KpiResponse other = createKpi("Other KPI", null, null, null);

        mockMvc.perform(authed(put("/api/hr/kpis/{id}", other.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateKpiRequest("existing kpi", null, null, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A KPI with this name already exists."));
    }

    @Test
    void updateMayChangeOnlyTheCaseOfItsOwnName() throws Exception {
        KpiResponse created = createKpi("Delivery Quality", null, null, null);

        mockMvc.perform(authed(put("/api/hr/kpis/{id}", created.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateKpiRequest("delivery quality", null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("delivery quality"));
    }

    @Test
    void updateOfUnknownKpiReturnsNotFound() throws Exception {
        mockMvc.perform(authed(put("/api/hr/kpis/{id}", 999_999L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateKpiRequest("Ghost KPI", null, null, null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("KPI not found."));
    }

    // ------------------------------------------------------ activate/deactivate

    @Test
    void deactivationOnlyFlipsTheActiveFlag() throws Exception {
        KpiResponse created = createKpi("Soft Deleted KPI", "Kept for history.",
                "Delivery", new BigDecimal("20.00"));

        mockMvc.perform(authed(patch("/api/hr/kpis/{id}/deactivate", created.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        KpiMaster stored = kpiRepository.findById(created.id()).orElseThrow();
        assertThat(stored.isActive()).isFalse();
        assertThat(stored.getName()).isEqualTo("Soft Deleted KPI");
        assertThat(stored.getDescription()).isEqualTo("Kept for history.");
    }

    @Test
    void reactivationRestoresTheKpi() throws Exception {
        KpiResponse created = createKpi("Revived KPI", null, null, null);
        mockMvc.perform(authed(patch("/api/hr/kpis/{id}/deactivate", created.id())))
                .andExpect(status().isOk());

        mockMvc.perform(authed(patch("/api/hr/kpis/{id}/reactivate", created.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        assertThat(kpiRepository.findById(created.id()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void repeatedDeactivationIsRejected() throws Exception {
        KpiResponse created = createKpi("Idempotency Check", null, null, null);
        mockMvc.perform(authed(patch("/api/hr/kpis/{id}/deactivate", created.id())))
                .andExpect(status().isOk());

        mockMvc.perform(authed(patch("/api/hr/kpis/{id}/deactivate", created.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("KPI is already inactive."));
    }

    @Test
    void repeatedReactivationIsRejected() throws Exception {
        KpiResponse created = createKpi("Still Active", null, null, null);

        mockMvc.perform(authed(patch("/api/hr/kpis/{id}/reactivate", created.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("KPI is already active."));
    }

    @Test
    void stateChangeOnUnknownKpiReturnsNotFound() throws Exception {
        mockMvc.perform(authed(patch("/api/hr/kpis/{id}/deactivate", 999_999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("KPI not found."));

        mockMvc.perform(authed(patch("/api/hr/kpis/{id}/reactivate", 999_999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("KPI not found."));
    }

    // ----------------------------------------------------------------- helpers

    private KpiResponse createKpi(String name, String description, String category,
                                  BigDecimal weightage) throws Exception {
        MvcResult result = mockMvc.perform(authed(post("/api/hr/kpis"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateKpiRequest(name, description, category, weightage))))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result);
    }

    private KpiResponse read(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), KpiResponse.class);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + hrToken);
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private static User user(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(PASSWORD_HASH);
        user.setRole(role);
        user.setEnabled(true);
        return user;
    }
}
