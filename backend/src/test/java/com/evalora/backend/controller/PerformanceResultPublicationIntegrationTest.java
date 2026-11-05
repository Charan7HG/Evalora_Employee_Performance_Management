package com.evalora.backend.controller;

import com.evalora.backend.dto.ConfigureCycleEmployeesRequest;
import com.evalora.backend.dto.ConfigureCycleKpisRequest;
import com.evalora.backend.dto.CycleEmployeeSelectionRequest;
import com.evalora.backend.dto.CycleKpiSelectionRequest;
import com.evalora.backend.dto.HrAssessmentEntryRequest;
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
import com.evalora.backend.service.HrAssessmentService;
import com.evalora.backend.service.ManagerAssessmentService;
import com.evalora.backend.service.PerformanceCycleService;
import com.evalora.backend.service.PerformanceResultService;
import com.evalora.backend.service.SelfAssessmentService;
import com.evalora.backend.support.CycleTestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_result_publication;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000"
})
class PerformanceResultPublicationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private EmployeeManagerAssignmentRepository managerAssignmentRepository;
    @Autowired private PerformanceCycleRepository cycleRepository;
    @Autowired private CycleKpiRepository cycleKpiRepository;
    @Autowired private EmployeeAssignmentRepository assignmentRepository;
    @Autowired private EmployeeKpiRepository employeeKpiRepository;
    @Autowired private SelfAssessmentRepository selfAssessmentRepository;
    @Autowired private ManagerAssessmentRepository managerAssessmentRepository;
    @Autowired private HrAssessmentRepository hrAssessmentRepository;
    @Autowired private PerformanceResultRepository resultRepository;
    @Autowired private PerformanceResultItemRepository itemRepository;
    @Autowired private KpiMasterRepository kpiRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PerformanceCycleService cycleService;
    @Autowired private SelfAssessmentService selfAssessmentService;
    @Autowired private ManagerAssessmentService managerAssessmentService;
    @Autowired private HrAssessmentService hrAssessmentService;
    @Autowired private PerformanceResultService resultService;
    @MockitoSpyBean private EmployeeAssignmentRepository assignmentRepositorySpy;

    private User hrUser;
    private Employee originalManager;
    private Employee newManager;
    private Employee employee;
    private Employee secondEmployee;
    private Employee thirdEmployee;
    private KpiMaster deliveryKpi;
    private KpiMaster qualityKpi;
    private Long cycleId;
    private Long assignmentId;
    private Long secondAssignmentId;
    private Long thirdAssignmentId;
    private Long firstEmployeeKpiId;
    private Long secondEmployeeKpiId;
    private String hrToken;
    private String employeeToken;
    private String secondEmployeeToken;
    private String originalManagerToken;
    private String newManagerToken;

    @BeforeEach
    void setUp() {
        clearDatabase();
        Mockito.reset(assignmentRepositorySpy);

        hrUser = userRepository.saveAndFlush(CycleTestData.user("hr.publication@evalora.local", Role.HR));
        originalManager = profile(Role.MANAGER, "MGR-PUB-1", "Original Manager");
        newManager = profile(Role.MANAGER, "MGR-PUB-2", "New Manager");
        employee = profile(Role.EMPLOYEE, "EMP-PUB-1", "Employee One");
        secondEmployee = profile(Role.EMPLOYEE, "EMP-PUB-2", "Employee Two");
        thirdEmployee = profile(Role.EMPLOYEE, "EMP-PUB-3", "Employee Three");
        employee.setManager(originalManager);
        secondEmployee.setManager(originalManager);
        thirdEmployee.setManager(originalManager);
        employeeRepository.saveAllAndFlush(List.of(employee, secondEmployee, thirdEmployee));

        deliveryKpi = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Delivery Quality", "Meets deadlines.", "Delivery"));
        deliveryKpi.setKpiType(KpiType.HR);
        deliveryKpi = kpiRepository.saveAndFlush(deliveryKpi);
        qualityKpi = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Quality", "Meets the quality bar.", "Quality"));
        qualityKpi.setKpiType(KpiType.HR);
        qualityKpi = kpiRepository.saveAndFlush(qualityKpi);

        cycleId = cycleService.create(CycleTestData.request("Publication 2026"), hrUser.getId()).id();
        cycleService.configureKpis(cycleId, new ConfigureCycleKpisRequest(List.of(
                new CycleKpiSelectionRequest(deliveryKpi.getId(), new BigDecimal("60.00")),
                new CycleKpiSelectionRequest(qualityKpi.getId(), new BigDecimal("40.00")))));
        cycleService.configureEmployees(cycleId, new ConfigureCycleEmployeesRequest(List.of(
                new CycleEmployeeSelectionRequest(employee.getId(), originalManager.getId()),
                new CycleEmployeeSelectionRequest(secondEmployee.getId(), originalManager.getId()),
                new CycleEmployeeSelectionRequest(thirdEmployee.getId(), originalManager.getId()))));
        cycleService.publish(cycleId);

        Map<Long, Long> assignmentsByEmployee = assignmentRepository
                .findByCycle_IdOrderByEmployee_IdAsc(cycleId).stream()
                .collect(Collectors.toMap(row -> row.getEmployee().getId(), EmployeeAssignment::getId));
        assignmentId = assignmentsByEmployee.get(employee.getId());
        secondAssignmentId = assignmentsByEmployee.get(secondEmployee.getId());
        thirdAssignmentId = assignmentsByEmployee.get(thirdEmployee.getId());
        List<EmployeeKpi> kpis = employeeKpiRepository.findByAssignment_IdOrderByIdAsc(assignmentId);
        firstEmployeeKpiId = kpis.get(0).getId();
        secondEmployeeKpiId = kpis.get(1).getId();

        submitSelfAndManager(employee, assignmentId, firstEmployeeKpiId, secondEmployeeKpiId);
        submitSelfAndManager(secondEmployee, secondAssignmentId,
                kpisFor(secondAssignmentId).get(0).getId(), kpisFor(secondAssignmentId).get(1).getId());
        submitSelfAndManager(thirdEmployee, thirdAssignmentId,
                kpisFor(thirdAssignmentId).get(0).getId(), kpisFor(thirdAssignmentId).get(1).getId());

        hrToken = jwtService.generateToken(hrUser);
        employeeToken = jwtService.generateToken(employee.getUser());
        secondEmployeeToken = jwtService.generateToken(secondEmployee.getUser());
        originalManagerToken = jwtService.generateToken(originalManager.getUser());
        newManagerToken = jwtService.generateToken(newManager.getUser());
    }

    @AfterEach
    void resetSpy() {
        Mockito.reset(assignmentRepositorySpy);
    }

    @Test
    void hrCanPublishFinalizedResultAndRepeatedPublishConflicts() throws Exception {
        PerformanceResult result = finalizeHrReview(assignmentId, firstEmployeeKpiId, secondEmployeeKpiId);
        List<PerformanceResultItem> beforeItems = itemRepository.findByResult_Id(result.getId());
        List<EmployeeKpi> snapshotsBefore =
                employeeKpiRepository.findByAssignment_IdOrderByIdAsc(assignmentId);
        List<SelfAssessment> selfBefore =
                selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId);
        List<ManagerAssessment> managerBefore =
                managerAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId);
        List<HrAssessment> hrBefore =
                hrAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId);
        BigDecimal scoreBefore = result.getOverallScore();
        PerformanceGrade gradeBefore = result.getGrade();
        var calculationModeBefore = result.getCalculationMode();
        OffsetDateTime computedAtBefore = result.getComputedAt();
        OffsetDateTime finalizedAt = assignmentRepository.findById(assignmentId)
                .orElseThrow().getHrFinalizedAt();

        MvcResult response = mockMvc.perform(withToken(
                        post("/api/hr/results/{id}/publish", result.getId()), hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedAt").isNotEmpty())
                .andExpect(jsonPath("$.overallScore").value(4.30))
                .andReturn();

        assertThat(response.getResponse().getContentAsString()).contains("\"resultId\"");
        PerformanceResult stored = resultRepository.findById(result.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ResultStatus.PUBLISHED);
        assertThat(stored.getOverallScore()).isEqualByComparingTo(scoreBefore);
        assertThat(stored.getGrade()).isEqualTo(gradeBefore);
        assertThat(stored.getCalculationMode()).isEqualTo(calculationModeBefore);
        assertThat(stored.getComputedAt()).isEqualTo(computedAtBefore);
        assertThat(stored.getPublishedBy().getId()).isEqualTo(hrUser.getId());
        assertThat(stored.getPublishedAt()).isNotNull();
        assertThat(assignmentRepository.findById(assignmentId).orElseThrow())
                .satisfies(assignment -> {
                    assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.RESULTS_PUBLISHED);
                    assertThat(assignment.getHrFinalizedAt()).isEqualTo(finalizedAt);
                    assertThat(assignment.getResultsPublishedAt()).isEqualTo(stored.getPublishedAt());
                });
        assertItemRowsUnchanged(beforeItems, itemRepository.findByResult_Id(result.getId()));
        assertSelfAssessmentRowsUnchanged(selfBefore,
                selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId));
        assertManagerAssessmentRowsUnchanged(managerBefore,
                managerAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId));
        assertHrAssessmentRowsUnchanged(hrBefore,
                hrAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId));
        List<EmployeeKpi> snapshotsAfter =
                employeeKpiRepository.findByAssignment_IdOrderByIdAsc(assignmentId);
        assertThat(snapshotsAfter).hasSameSizeAs(snapshotsBefore);
        for (int index = 0; index < snapshotsBefore.size(); index++) {
            assertThat(snapshotsAfter.get(index).getId()).isEqualTo(snapshotsBefore.get(index).getId());
            assertThat(snapshotsAfter.get(index).getAssignment().getId())
                    .isEqualTo(snapshotsBefore.get(index).getAssignment().getId());
            assertThat(snapshotsAfter.get(index).getKpi().getId())
                    .isEqualTo(snapshotsBefore.get(index).getKpi().getId());
            assertThat(snapshotsAfter.get(index).getKpiName()).isEqualTo(snapshotsBefore.get(index).getKpiName());
            assertThat(snapshotsAfter.get(index).getKpiDescription())
                    .isEqualTo(snapshotsBefore.get(index).getKpiDescription());
            assertThat(snapshotsAfter.get(index).getKpiCategory())
                    .isEqualTo(snapshotsBefore.get(index).getKpiCategory());
            assertThat(snapshotsAfter.get(index).getWeightage())
                    .isEqualTo(snapshotsBefore.get(index).getWeightage());
            assertThat(snapshotsAfter.get(index).getCreatedAt()).isEqualTo(snapshotsBefore.get(index).getCreatedAt());
            assertThat(snapshotsAfter.get(index).getUpdatedAt()).isEqualTo(snapshotsBefore.get(index).getUpdatedAt());
        }

        mockMvc.perform(withToken(post("/api/hr/results/{id}/publish", result.getId()), hrToken))
                .andExpect(status().isConflict());
    }

    @Test
    void hrCanListAndReadResultsAwaitingPublication() throws Exception {
        PerformanceResult result = finalizeHrReview(assignmentId, firstEmployeeKpiId, secondEmployeeKpiId);
        mockMvc.perform(withToken(get("/api/hr/results"), hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].resultId").value(result.getId()))
                .andExpect(jsonPath("$[0].status").value("DRAFT"));

        mockMvc.perform(withToken(get("/api/hr/results/{id}", result.getId()), hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void hrCanListEveryResultOfOneCycleAndFilterByDepartment() throws Exception {
        PerformanceResult first = finalizeHrReview(assignmentId, firstEmployeeKpiId, secondEmployeeKpiId);
        List<EmployeeKpi> otherKpis = kpisFor(secondAssignmentId);
        PerformanceResult second = finalizeHrReview(secondAssignmentId,
                otherKpis.get(0).getId(), otherKpis.get(1).getId());

        mockMvc.perform(withToken(get("/api/hr/results/cycles/{id}", cycleId), hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("DRAFT"))
                .andExpect(jsonPath("$[1].status").value("DRAFT"));

        Employee qaEmployee = employeeRepository.findById(secondEmployee.getId()).orElseThrow();
        qaEmployee.setDepartment("QA");
        employeeRepository.saveAndFlush(qaEmployee);

        mockMvc.perform(withToken(
                        get("/api/hr/results/cycles/{id}", cycleId).param("department", "qa"),
                        hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].resultId").value(second.getId()))
                .andExpect(jsonPath("$[0].employee.department").value("QA"));

        mockMvc.perform(withToken(
                        get("/api/hr/results/cycles/{id}", cycleId).param("department", "Finance"),
                        hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(withToken(get("/api/hr/results/cycles/{id}", cycleId), employeeToken))
                .andExpect(status().isForbidden());
        assertThat(resultRepository.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(ResultStatus.DRAFT);
    }

    @Test
    void onlyFinalizedResultsInPublishedCyclesCanBePublished() throws Exception {
        PerformanceResult pendingResult = createDraftResultFor(secondAssignmentId);
        mockMvc.perform(withToken(post("/api/hr/results/{id}/publish", pendingResult.getId()), hrToken))
                .andExpect(status().isConflict());
        assertThat(resultRepository.findById(pendingResult.getId()).orElseThrow().getStatus())
                .isEqualTo(ResultStatus.DRAFT);

        PerformanceResult finalized = finalizeHrReview(assignmentId, firstEmployeeKpiId, secondEmployeeKpiId);
        PerformanceCycle cycle = cycleRepository.findById(cycleId).orElseThrow();
        cycle.setStatus(CycleStatus.COMPLETED);
        cycleRepository.saveAndFlush(cycle);
        mockMvc.perform(withToken(post("/api/hr/results/{id}/publish", finalized.getId()), hrToken))
                .andExpect(status().isConflict());
        assertThat(resultRepository.findById(finalized.getId()).orElseThrow().getStatus())
                .isEqualTo(ResultStatus.DRAFT);
    }

    @Test
    void unknownResultCannotBePublished() throws Exception {
        mockMvc.perform(withToken(post("/api/hr/results/{id}/publish", 999999L), hrToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void bulkPublicationPublishesEligibleResultsAndSkipsOtherAssignments() throws Exception {
        PerformanceResult eligible = finalizeHrReview(assignmentId, firstEmployeeKpiId, secondEmployeeKpiId);
        BigDecimal scoreBefore = eligible.getOverallScore();
        PerformanceGrade gradeBefore = eligible.getGrade();
        var modeBefore = eligible.getCalculationMode();
        List<PerformanceResultItem> itemsBefore = itemRepository.findByResult_Id(eligible.getId());
        PerformanceResult pendingResult = createDraftResultFor(secondAssignmentId);
        EmployeeAssignment completedWithoutResult = assignmentRepository.findById(thirdAssignmentId).orElseThrow();
        completedWithoutResult.setHrFinalizedAt(OffsetDateTime.now(ZoneOffset.UTC));
        completedWithoutResult.setStatus(AssignmentStatus.COMPLETED);
        assignmentRepository.saveAndFlush(completedWithoutResult);

        mockMvc.perform(withToken(post("/api/hr/results/cycles/{id}/publish", cycleId), hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleId").value(cycleId))
                .andExpect(jsonPath("$.publishedCount").value(1))
                .andExpect(jsonPath("$.skippedCount").value(2))
                .andExpect(jsonPath("$.publishedResultIds[0]").value(eligible.getId()));

        assertThat(resultRepository.count()).isEqualTo(2);
        assertThat(resultRepository.findById(eligible.getId()).orElseThrow().getStatus())
                .isEqualTo(ResultStatus.PUBLISHED);
        PerformanceResult stored = resultRepository.findById(eligible.getId()).orElseThrow();
        assertThat(stored.getOverallScore()).isEqualByComparingTo(scoreBefore);
        assertThat(stored.getGrade()).isEqualTo(gradeBefore);
        assertThat(stored.getCalculationMode()).isEqualTo(modeBefore);
        assertThat(assignmentRepository.findById(assignmentId).orElseThrow().getResultsPublishedAt())
                .isEqualTo(stored.getPublishedAt());
        assertItemRowsUnchanged(itemsBefore, itemRepository.findByResult_Id(eligible.getId()));
        assertThat(resultRepository.findById(pendingResult.getId()).orElseThrow().getStatus())
                .isEqualTo(ResultStatus.DRAFT);
        assertThat(assignmentRepository.findById(secondAssignmentId).orElseThrow().getStatus())
                .isEqualTo(AssignmentStatus.HR_REVIEW_PENDING);
        assertThat(resultRepository.findByAssignment_Id(thirdAssignmentId)).isEmpty();

        OffsetDateTime firstPublishedAt = stored.getPublishedAt();
        Long firstPublisherId = stored.getPublishedBy().getId();
        mockMvc.perform(withToken(post("/api/hr/results/cycles/{id}/publish", cycleId), hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(3));
        assertThat(resultRepository.count()).isEqualTo(2);
        PerformanceResult afterRepeat = resultRepository.findById(eligible.getId()).orElseThrow();
        assertThat(afterRepeat.getPublishedAt()).isEqualTo(firstPublishedAt);
        assertThat(afterRepeat.getPublishedBy().getId()).isEqualTo(firstPublisherId);
    }

    @Test
    void bulkPublicationDoesNotCreateMissingResultsAndRejectsInvalidCycle() throws Exception {
        mockMvc.perform(withToken(post("/api/hr/results/cycles/{id}/publish", cycleId), hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(3));
        assertThat(resultRepository.count()).isZero();

        PerformanceCycle cycle = cycleRepository.findById(cycleId).orElseThrow();
        cycle.setStatus(CycleStatus.COMPLETED);
        cycleRepository.saveAndFlush(cycle);
        mockMvc.perform(withToken(post("/api/hr/results/cycles/{id}/publish", cycleId), hrToken))
                .andExpect(status().isConflict());
        mockMvc.perform(withToken(post("/api/hr/results/cycles/{id}/publish", 999999L), hrToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void employeeCanReadOnlyOwnPublishedResult() throws Exception {
        PerformanceResult result = finalizeHrReview(assignmentId, firstEmployeeKpiId, secondEmployeeKpiId);
        resultService.publish(hrUser.getId(), result.getId());

        mockMvc.perform(withToken(get("/api/employee/results"), employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].employee.employeeCode").value("EMP-PUB-1"))
                .andExpect(jsonPath("$[0].items.length()").value(2))
                .andExpect(jsonPath("$[0].items[0].kpiName").value("Delivery Quality"));
        mockMvc.perform(withToken(get("/api/employee/results/{id}", result.getId()), employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallScore").value(4.30))
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        mockMvc.perform(withToken(get("/api/employee/results/{id}", result.getId()), secondEmployeeToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void employeeCannotReadDraftOrResultsWithInconsistentPublicationState() throws Exception {
        PerformanceResult result = finalizeHrReview(assignmentId, firstEmployeeKpiId, secondEmployeeKpiId);
        mockMvc.perform(withToken(get("/api/employee/results"), employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(withToken(get("/api/employee/results/{id}", result.getId()), employeeToken))
                .andExpect(status().isNotFound());

        result.setStatus(ResultStatus.PUBLISHED);
        result.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        result.setPublishedBy(hrUser);
        resultRepository.saveAndFlush(result);
        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assignment.setStatus(AssignmentStatus.RESULTS_PUBLISHED);
        assignment.setResultsPublishedAt(null);
        assignmentRepository.saveAndFlush(assignment);
        assertEmployeeResultHidden(result.getId());

        assignment.setResultsPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        assignment.setStatus(AssignmentStatus.RESULTS_PUBLISHED);
        assignmentRepository.saveAndFlush(assignment);
        result.setPublishedAt(null);
        resultRepository.saveAndFlush(result);
        assertEmployeeResultHidden(result.getId());

        result.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        resultRepository.saveAndFlush(result);
        assignment.setResultsPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        assignment.setStatus(AssignmentStatus.COMPLETED);
        assignmentRepository.saveAndFlush(assignment);
        assertEmployeeResultHidden(result.getId());

        assignment.setStatus(AssignmentStatus.RESULTS_PUBLISHED);
        assignment.setResultsPublishedAt(result.getPublishedAt().plusSeconds(1));
        assignmentRepository.saveAndFlush(assignment);
        assertEmployeeResultHidden(result.getId());
    }

    @Test
    void managerHistoricalAccessUsesTheAssignmentManagerSnapshot() throws Exception {
        PerformanceResult result = finalizeHrReview(assignmentId, firstEmployeeKpiId, secondEmployeeKpiId);
        resultService.publish(hrUser.getId(), result.getId());

        employee.setManager(newManager);
        employeeRepository.saveAndFlush(employee);

        mockMvc.perform(withToken(get("/api/manager/results"), originalManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].employee.employeeCode").value("EMP-PUB-1"));
        mockMvc.perform(withToken(get("/api/manager/results/{id}", result.getId()), originalManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultId").value(result.getId()));
        mockMvc.perform(withToken(get("/api/manager/results/{id}", result.getId()), newManagerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void managerCannotReadDraftOrResultWithNullPublicationTimestamp() throws Exception {
        PerformanceResult result = finalizeHrReview(assignmentId, firstEmployeeKpiId, secondEmployeeKpiId);
        mockMvc.perform(withToken(get("/api/manager/results/{id}", result.getId()), originalManagerToken))
                .andExpect(status().isNotFound());

        result.setStatus(ResultStatus.PUBLISHED);
        result.setPublishedAt(null);
        result.setPublishedBy(hrUser);
        resultRepository.saveAndFlush(result);
        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assignment.setStatus(AssignmentStatus.RESULTS_PUBLISHED);
        assignment.setResultsPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        assignmentRepository.saveAndFlush(assignment);
        mockMvc.perform(withToken(get("/api/manager/results/{id}", result.getId()), originalManagerToken))
                .andExpect(status().isNotFound());

        result.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        resultRepository.saveAndFlush(result);
        mockMvc.perform(withToken(get("/api/manager/results/{id}", result.getId()), originalManagerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void resultEndpointsEnforceRolesAndUnauthenticatedAccess() throws Exception {
        PerformanceResult result = finalizeHrReview(assignmentId, firstEmployeeKpiId, secondEmployeeKpiId);
        for (String path : List.of(
                "/api/hr/results",
                "/api/hr/results/" + result.getId(),
                "/api/hr/results/" + result.getId() + "/publish",
                "/api/hr/results/cycles/" + cycleId + "/publish",
                "/api/employee/results",
                "/api/employee/results/" + result.getId(),
                "/api/manager/results",
                "/api/manager/results/" + result.getId())) {
            MockHttpServletRequestBuilder request = path.startsWith("/api/hr/") && path.endsWith("/publish")
                    ? post(path) : get(path);
            mockMvc.perform(request).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(withToken(post("/api/hr/results/{id}/publish", result.getId()), employeeToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(post("/api/hr/results/cycles/{id}/publish", cycleId), employeeToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(get("/api/employee/results"), hrToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(get("/api/manager/results"), employeeToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void individualAndBulkPublicationAreSafeWhenRacing() throws Exception {
        PerformanceResult result = finalizeHrReview(assignmentId, firstEmployeeKpiId, secondEmployeeKpiId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Object> individual = executor.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    return resultService.publish(hrUser.getId(), result.getId());
                } catch (Throwable failure) {
                    return failure;
                }
            });
            Future<Object> bulk = executor.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    return resultService.publishCycle(hrUser.getId(), cycleId);
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Object individualOutcome = individual.get(30, TimeUnit.SECONDS);
            Object bulkOutcome = bulk.get(30, TimeUnit.SECONDS);
            assertThat(bulkOutcome).isInstanceOf(com.evalora.backend.dto.BulkResultPublicationResponse.class);
            assertThat(individualOutcome instanceof com.evalora.backend.dto.PerformanceResultResponse
                    || individualOutcome instanceof com.evalora.backend.exception.HrApiException).isTrue();
            var bulkResponse = (com.evalora.backend.dto.BulkResultPublicationResponse) bulkOutcome;
            if (individualOutcome instanceof com.evalora.backend.exception.HrApiException failure) {
                assertThat(failure.getType()).isEqualTo(com.evalora.backend.exception.HrErrorType.CONFLICT);
            }
            int individualSuccess = individualOutcome instanceof com.evalora.backend.dto.PerformanceResultResponse
                    ? 1 : 0;
            assertThat(individualSuccess + bulkResponse.publishedCount()).isEqualTo(1);
            var stored = resultRepository.findById(result.getId()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(ResultStatus.PUBLISHED);
            assertThat(assignmentRepository.findById(assignmentId).orElseThrow().getResultsPublishedAt())
                    .isEqualTo(stored.getPublishedAt());
            assertThat(resultRepository.count()).isEqualTo(1);
            assertThat(itemRepository.findByResult_Id(result.getId())).hasSize(2);
            assertThat(assignmentRepository.findById(assignmentId).orElseThrow().getStatus())
                    .isEqualTo(AssignmentStatus.RESULTS_PUBLISHED);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void bulkPersistenceFailureRollsBackAllPublicationChanges() {
        PerformanceResult result = finalizeHrReview(assignmentId, firstEmployeeKpiId, secondEmployeeKpiId);
        List<EmployeeKpi> secondKpis = kpisFor(secondAssignmentId);
        PerformanceResult secondResult =
                finalizeHrReview(secondAssignmentId, secondKpis.get(0).getId(), secondKpis.get(1).getId());
        doThrow(new DataIntegrityViolationException("simulated publication failure"))
                .when(assignmentRepositorySpy).saveAll(any(Iterable.class));

        assertThatThrownBy(() -> resultService.publishCycle(hrUser.getId(), cycleId))
                .isInstanceOf(DataIntegrityViolationException.class);

        PerformanceResult stored = resultRepository.findById(result.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ResultStatus.DRAFT);
        assertThat(stored.getPublishedAt()).isNull();
        assertThat(stored.getPublishedBy()).isNull();
        assertThat(assignmentRepository.findById(assignmentId).orElseThrow().getStatus())
                .isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(assignmentRepository.findById(assignmentId).orElseThrow().getResultsPublishedAt())
                .isNull();
        PerformanceResult secondStored = resultRepository.findById(secondResult.getId()).orElseThrow();
        assertThat(secondStored.getStatus()).isEqualTo(ResultStatus.DRAFT);
        assertThat(secondStored.getPublishedAt()).isNull();
        assertThat(secondStored.getPublishedBy()).isNull();
        assertThat(assignmentRepository.findById(secondAssignmentId).orElseThrow().getStatus())
                .isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(assignmentRepository.findById(secondAssignmentId).orElseThrow().getResultsPublishedAt())
                .isNull();
    }

    @Test
    void individualPersistenceFailureRollsBackBothPublicationStatuses() {
        PerformanceResult result = finalizeHrReview(assignmentId, firstEmployeeKpiId, secondEmployeeKpiId);
        doThrow(new DataIntegrityViolationException("simulated publication failure"))
                .when(assignmentRepositorySpy).saveAndFlush(any(EmployeeAssignment.class));

        assertThatThrownBy(() -> resultService.publish(hrUser.getId(), result.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        PerformanceResult stored = resultRepository.findById(result.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ResultStatus.DRAFT);
        assertThat(stored.getPublishedAt()).isNull();
        assertThat(stored.getPublishedBy()).isNull();
        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(assignment.getResultsPublishedAt()).isNull();
    }

    private void assertEmployeeResultHidden(Long resultId) throws Exception {
        mockMvc.perform(withToken(get("/api/employee/results/{id}", resultId), employeeToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(get("/api/employee/results"), employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private PerformanceResult finalizeHrReview(Long currentAssignmentId, Long firstKpiId, Long secondKpiId) {
        hrAssessmentService.saveDraft(hrUser.getId(), currentAssignmentId, new SaveHrAssessmentDraftRequest(List.of(
                new HrAssessmentEntryRequest(firstKpiId, new BigDecimal("5"), "HR delivery."),
                new HrAssessmentEntryRequest(secondKpiId, new BigDecimal("4"), "HR quality."))));
        hrAssessmentService.submit(hrUser.getId(), currentAssignmentId);
        return resultRepository.findByAssignment_Id(currentAssignmentId).orElseThrow();
    }

    private PerformanceResult createDraftResultFor(Long currentAssignmentId) {
        PerformanceResult result = new PerformanceResult();
        result.setAssignment(assignmentRepository.findById(currentAssignmentId).orElseThrow());
        result.setOverallScore(new BigDecimal("4.00"));
        result.setGrade(PerformanceGrade.GOOD);
        result.setCalculationMode(com.evalora.backend.entity.enums.CalculationMode.AVERAGE);
        result.setStatus(ResultStatus.DRAFT);
        result.setComputedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return resultRepository.saveAndFlush(result);
    }

    private void submitSelfAndManager(Employee target, Long targetAssignmentId, Long firstKpiId, Long secondKpiId) {
        selfAssessmentService.saveDraft(target.getUser().getId(), targetAssignmentId,
                new SaveSelfAssessmentDraftRequest(List.of(
                        new SelfAssessmentEntryRequest(firstKpiId, new BigDecimal("4"), "Self one."),
                        new SelfAssessmentEntryRequest(secondKpiId, new BigDecimal("5"), "Self two."))));
        selfAssessmentService.submit(target.getUser().getId(), targetAssignmentId);
        managerAssessmentService.saveDraft(originalManager.getUser().getId(), targetAssignmentId,
                new SaveManagerAssessmentDraftRequest(List.of(
                        new ManagerAssessmentEntryRequest(firstKpiId, new BigDecimal("4"), "Manager one."),
                        new ManagerAssessmentEntryRequest(secondKpiId, new BigDecimal("4"), "Manager two."))));
        managerAssessmentService.submit(originalManager.getUser().getId(), targetAssignmentId);
    }

    private List<EmployeeKpi> kpisFor(Long targetAssignmentId) {
        return employeeKpiRepository.findByAssignment_IdOrderByIdAsc(targetAssignmentId);
    }

    private Employee profile(Role role, String code, String name) {
        User user = userRepository.saveAndFlush(
                CycleTestData.user(code.toLowerCase() + "@evalora.local", role));
        return employeeRepository.saveAndFlush(CycleTestData.profile(user, code, name));
    }

    private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static void assertItemRowsUnchanged(List<PerformanceResultItem> before,
                                                List<PerformanceResultItem> after) {
        Map<Long, PerformanceResultItem> beforeByKpi = before.stream()
                .collect(Collectors.toMap(item -> item.getEmployeeKpi().getId(), Function.identity()));
        assertThat(after).hasSameSizeAs(before);
        assertThat(after).allSatisfy(item -> {
            PerformanceResultItem original = beforeByKpi.get(item.getEmployeeKpi().getId());
            assertThat(item.getKpiName()).isEqualTo(original.getKpiName());
            assertThat(item.getId()).isEqualTo(original.getId());
            assertThat(item.getResult().getId()).isEqualTo(original.getResult().getId());
            assertThat(item.getEmployeeKpi().getId()).isEqualTo(original.getEmployeeKpi().getId());
            assertThat(item.getWeightage()).isEqualTo(original.getWeightage());
            assertThat(item.getSelfRating()).isEqualTo(original.getSelfRating());
            assertThat(item.getSelfComment()).isEqualTo(original.getSelfComment());
            assertThat(item.getManagerRating()).isEqualTo(original.getManagerRating());
            assertThat(item.getManagerComment()).isEqualTo(original.getManagerComment());
            assertThat(item.getHrRating()).isEqualTo(original.getHrRating());
            assertThat(item.getHrComment()).isEqualTo(original.getHrComment());
            assertThat(item.getFinalScore()).isEqualTo(original.getFinalScore());
            assertThat(item.getCreatedAt()).isEqualTo(original.getCreatedAt());
            assertThat(item.getUpdatedAt()).isEqualTo(original.getUpdatedAt());
        });
    }

    private static void assertSelfAssessmentRowsUnchanged(List<SelfAssessment> before,
                                                          List<SelfAssessment> after) {
        assertThat(after).hasSameSizeAs(before);
        Map<Long, SelfAssessment> beforeByKpi = before.stream()
                .collect(Collectors.toMap(row -> row.getEmployeeKpi().getId(), Function.identity()));
        for (SelfAssessment row : after) {
            SelfAssessment original = beforeByKpi.get(row.getEmployeeKpi().getId());
            assertThat(row.getId()).isEqualTo(original.getId());
            assertThat(row.getRating()).isEqualTo(original.getRating());
            assertThat(row.getComment()).isEqualTo(original.getComment());
            assertThat(row.getCreatedAt()).isEqualTo(original.getCreatedAt());
            assertThat(row.getUpdatedAt()).isEqualTo(original.getUpdatedAt());
        }
    }

    private static void assertManagerAssessmentRowsUnchanged(List<ManagerAssessment> before,
                                                             List<ManagerAssessment> after) {
        assertThat(after).hasSameSizeAs(before);
        Map<Long, ManagerAssessment> beforeByKpi = before.stream()
                .collect(Collectors.toMap(row -> row.getEmployeeKpi().getId(), Function.identity()));
        for (ManagerAssessment row : after) {
            ManagerAssessment original = beforeByKpi.get(row.getEmployeeKpi().getId());
            assertThat(row.getId()).isEqualTo(original.getId());
            assertThat(row.getManager().getId()).isEqualTo(original.getManager().getId());
            assertThat(row.getRating()).isEqualTo(original.getRating());
            assertThat(row.getComment()).isEqualTo(original.getComment());
            assertThat(row.getCreatedAt()).isEqualTo(original.getCreatedAt());
            assertThat(row.getUpdatedAt()).isEqualTo(original.getUpdatedAt());
        }
    }

    private static void assertHrAssessmentRowsUnchanged(List<HrAssessment> before, List<HrAssessment> after) {
        assertThat(after).hasSameSizeAs(before);
        Map<Long, HrAssessment> beforeByKpi = before.stream()
                .collect(Collectors.toMap(row -> row.getEmployeeKpi().getId(), Function.identity()));
        for (HrAssessment row : after) {
            HrAssessment original = beforeByKpi.get(row.getEmployeeKpi().getId());
            assertThat(row.getId()).isEqualTo(original.getId());
            assertThat(row.getHr().getId()).isEqualTo(original.getHr().getId());
            assertThat(row.getRating()).isEqualTo(original.getRating());
            assertThat(row.getComment()).isEqualTo(original.getComment());
            assertThat(row.getCreatedAt()).isEqualTo(original.getCreatedAt());
            assertThat(row.getUpdatedAt()).isEqualTo(original.getUpdatedAt());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the latch.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private void clearDatabase() {
        itemRepository.deleteAll();
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
