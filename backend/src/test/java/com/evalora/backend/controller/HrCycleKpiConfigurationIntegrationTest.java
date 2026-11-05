package com.evalora.backend.controller;

import com.evalora.backend.dto.CycleKpiSelectionRequest;
import com.evalora.backend.dto.CycleRequest;
import com.evalora.backend.dto.CycleResponse;
import com.evalora.backend.dto.ConfigureCycleKpisRequest;
import com.evalora.backend.entity.CycleKpi;
import com.evalora.backend.entity.KpiMaster;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3D KPI selection and weightage rules for a DRAFT cycle.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_cycle_kpi;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
class HrCycleKpiConfigurationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

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
    private KpiMasterRepository kpiRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private User hrUser;
    private String hrToken;
    private Long cycleId;
    private KpiMaster delivery;
    private KpiMaster quality;

    @BeforeEach
    void setUp() {
        clearDatabase();

        hrUser = userRepository.saveAndFlush(CycleTestData.user("hr.cycle.kpi@evalora.local", Role.HR));
        hrToken = jwtService.generateToken(hrUser);

        CycleResponse cycle = cycleService.create(CycleTestData.request("Annual 2026"), hrUser.getId());
        cycleId = cycle.id();

        delivery = kpiRepository.saveAndFlush(
                kpi("Delivery Quality", "Meets agreed deadlines.", "Delivery", "10.00"));
        quality = kpiRepository.saveAndFlush(
                kpi("Quality", "Work meets the quality bar.", "Quality", "15.00"));
    }

    // ------------------------------------------------------------------ accept

    @Test
    void activeKpisCanBeSelectedWithoutWeights() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(delivery.getId(), null),
                        new CycleKpiSelectionRequest(quality.getId(), null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].kpiId").value(delivery.getId()))
                .andExpect(jsonPath("$[0].name").value("Delivery Quality"))
                .andExpect(jsonPath("$[0].description").value("Meets agreed deadlines."))
                .andExpect(jsonPath("$[0].category").value("Delivery"))
                .andExpect(jsonPath("$[0].weightage").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[1].kpiId").value(quality.getId()));

        List<CycleKpi> stored = cycleKpiRepository.findByCycle_IdOrderByIdAsc(cycleId);
        assertThat(stored).hasSize(2);
        assertThat(stored).allSatisfy(row -> assertThat(row.getWeightage()).isNull());
    }

    @Test
    void weightedSelectionTotallingOneHundredIsAccepted() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(delivery.getId(), new BigDecimal("60.00")),
                        new CycleKpiSelectionRequest(quality.getId(), new BigDecimal("40.00")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].weightage").value(60.00))
                .andExpect(jsonPath("$[1].weightage").value(40.00));

        List<CycleKpi> stored = cycleKpiRepository.findByCycle_IdOrderByIdAsc(cycleId);
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).getWeightage()).isEqualByComparingTo("60.00");
        assertThat(stored.get(1).getWeightage()).isEqualByComparingTo("40.00");
    }

    @Test
    void aSingleKpiCanCarryTheWholeWeight() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(delivery.getId(), new BigDecimal("100.00")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].weightage").value(100.00));
    }

    @Test
    void threeDecimalFreeWeightsTotallingOneHundredAreAccepted() throws Exception {
        KpiMaster third = kpiRepository.saveAndFlush(
                kpi("Ownership", "Owns the outcome.", "Behaviour", null));

        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(delivery.getId(), new BigDecimal("33.33")),
                        new CycleKpiSelectionRequest(quality.getId(), new BigDecimal("33.33")),
                        new CycleKpiSelectionRequest(third.getId(), new BigDecimal("33.34")))))
                .andExpect(status().isOk());
    }

    @Test
    void configuringCycleWeightsDoesNotChangeMasterDefaults() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(delivery.getId(), new BigDecimal("60.00")),
                        new CycleKpiSelectionRequest(quality.getId(), new BigDecimal("40.00")))))
                .andExpect(status().isOk());

        assertThat(kpiRepository.findById(delivery.getId()).orElseThrow().getDefaultWeightage())
                .isEqualByComparingTo("10.00");
        assertThat(kpiRepository.findById(quality.getId()).orElseThrow().getDefaultWeightage())
                .isEqualByComparingTo("15.00");
    }

    // ------------------------------------------------------------------ reject

    @Test
    void inactiveKpiIsRejected() throws Exception {
        KpiMaster inactive = kpiRepository.saveAndFlush(
                kpi("Retired KPI", null, null, null));
        inactive.setActive(false);
        kpiRepository.saveAndFlush(inactive);

        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(inactive.getId(), null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only an active KPI can be selected."));

        assertThat(cycleKpiRepository.findByCycle_IdOrderByIdAsc(cycleId)).isEmpty();
    }

    @Test
    void unknownKpiIsRejected() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(999_999L, null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("KPI not found."));
    }

    @Test
    void duplicateKpiSelectionIsRejected() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(delivery.getId(), null),
                        new CycleKpiSelectionRequest(delivery.getId(), null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The same KPI cannot be selected twice in one cycle."));

        assertThat(cycleKpiRepository.findByCycle_IdOrderByIdAsc(cycleId)).isEmpty();
    }

    @Test
    void partiallyConfiguredWeightsAreRejected() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(delivery.getId(), new BigDecimal("60.00")),
                        new CycleKpiSelectionRequest(quality.getId(), null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("KPI weightage must be set for every KPI or for none."));
    }

    @Test
    void weightsNotTotallingOneHundredAreRejected() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(delivery.getId(), new BigDecimal("40.00")),
                        new CycleKpiSelectionRequest(quality.getId(), new BigDecimal("50.00")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("KPI weightages must total exactly 100.00."));
    }

    @Test
    void zeroWeightIsRejected() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(delivery.getId(), new BigDecimal("0.00")),
                        new CycleKpiSelectionRequest(quality.getId(), new BigDecimal("100.00")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("KPI weightage must be greater than 0 and at most 100."));
    }

    @Test
    void negativeWeightIsRejected() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(delivery.getId(), new BigDecimal("-5.00")),
                        new CycleKpiSelectionRequest(quality.getId(), new BigDecimal("105.00")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("KPI weightage must be greater than 0 and at most 100."));
    }

    @Test
    void weightAboveOneHundredIsRejected() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(delivery.getId(), new BigDecimal("100.01")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("KPI weightage must be greater than 0 and at most 100."));
    }

    @Test
    void weightWithTooManyDecimalsIsRejected() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(delivery.getId(), new BigDecimal("12.345")),
                        new CycleKpiSelectionRequest(quality.getId(), new BigDecimal("87.655")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("KPI weightage must have at most 2 decimal places."));
    }

    @Test
    void invalidReplacementLeavesThePreviousConfigurationUnchanged() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(delivery.getId(), new BigDecimal("60.00")),
                        new CycleKpiSelectionRequest(quality.getId(), new BigDecimal("40.00")))))
                .andExpect(status().isOk());

        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(quality.getId(), new BigDecimal("70.00")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("KPI weightages must total exactly 100.00."));

        mockMvc.perform(authed(get("/api/hr/cycles/{id}/kpis", cycleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].kpiId").value(delivery.getId()))
                .andExpect(jsonPath("$[0].weightage").value(60.00))
                .andExpect(jsonPath("$[1].kpiId").value(quality.getId()))
                .andExpect(jsonPath("$[1].weightage").value(40.00));
    }

    @Test
    void fullReplacementRemovesDeselectedKpis() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(delivery.getId(), null),
                        new CycleKpiSelectionRequest(quality.getId(), null))))
                .andExpect(status().isOk());

        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(quality.getId(), null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kpiId").value(quality.getId()));

        assertThat(cycleKpiRepository.findByCycle_IdOrderByIdAsc(cycleId))
                .extracting(row -> row.getKpi().getId())
                .containsExactly(quality.getId());
    }

    @Test
    void emptySelectionClearsTheConfiguration() throws Exception {
        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(delivery.getId(), null))))
                .andExpect(status().isOk());

        mockMvc.perform(configure(List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        assertThat(cycleKpiRepository.findByCycle_IdOrderByIdAsc(cycleId)).isEmpty();
    }

    @Test
    void publishedCycleCannotBeReconfigured() throws Exception {
        markPublished(cycleId);

        mockMvc.perform(configure(List.of(
                        new CycleKpiSelectionRequest(delivery.getId(), null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only a DRAFT cycle can be modified."));
    }

    @Test
    void readingKpisOfAnUnknownCycleReturnsNotFound() throws Exception {
        mockMvc.perform(authed(get("/api/hr/cycles/{id}/kpis", 999_999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Performance cycle not found."));
    }

    // ----------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder configure(
            List<CycleKpiSelectionRequest> selections) {
        return authed(post("/api/hr/cycles/{id}/kpis", cycleId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new ConfigureCycleKpisRequest(selections)));
    }

    private void markPublished(Long id) {
        PerformanceCycle cycle = cycleRepository.findById(id).orElseThrow();
        cycle.setStatus(CycleStatus.PUBLISHED);
        cycle.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        cycleRepository.saveAndFlush(cycle);
    }

    private KpiMaster kpi(String name, String description, String category, String defaultWeight) {
        KpiMaster kpi = CycleTestData.kpi(name, description, category);
        kpi.setDefaultWeightage(defaultWeight == null ? null : new BigDecimal(defaultWeight));
        kpi.setCreatedBy(hrUser);
        return kpi;
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + hrToken);
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
