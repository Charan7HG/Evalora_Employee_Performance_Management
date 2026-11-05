package com.evalora.backend.service;

import com.evalora.backend.dto.ConfigureCycleEmployeesRequest;
import com.evalora.backend.dto.ConfigureCycleKpisRequest;
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
import com.evalora.backend.entity.KpiMaster;
import com.evalora.backend.entity.PerformanceResult;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.AssignmentStatus;
import com.evalora.backend.entity.enums.CalculationMode;
import com.evalora.backend.entity.enums.KpiType;
import com.evalora.backend.entity.enums.PerformanceGrade;
import com.evalora.backend.entity.enums.ResultStatus;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.exception.HrErrorType;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that HR draft saving and submission serialise on the assignment row.
 *
 * <p>H2 is used only for the Hibernate-generated schema and JPA row locking.
 * PostgreSQL-specific lock semantics are not proven here.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_hr_assessment_concurrency;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000"
})
class HrAssessmentConcurrencyIntegrationTest {

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User hrUser;
    private Employee manager;
    private Employee employee;
    private Long assignmentId;
    private Long firstEmployeeKpiId;
    private Long secondEmployeeKpiId;

    @BeforeEach
    void setUp() {
        clearDatabase();

        hrUser = userRepository.saveAndFlush(
                CycleTestData.user("hr.assessor.concurrency@evalora.local", Role.HR));
        manager = profile(Role.MANAGER, "MGR-HR-CON", "Mona Manager");
        employee = profile(Role.EMPLOYEE, "EMP-HR-CON", "Eve Employee");

        KpiMaster delivery = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Delivery Quality", "Meets agreed deadlines.", "Delivery"));
        delivery.setKpiType(KpiType.HR);
        delivery = kpiRepository.saveAndFlush(delivery);
        KpiMaster quality = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Quality", "Work meets the quality bar.", "Quality"));
        quality.setKpiType(KpiType.HR);
        quality = kpiRepository.saveAndFlush(quality);

        Long cycleId = cycleService.create(CycleTestData.request("Annual 2026"), hrUser.getId()).id();
        cycleService.configureKpis(cycleId, new ConfigureCycleKpisRequest(List.of(
                new CycleKpiSelectionRequest(delivery.getId(), new BigDecimal("60.00")),
                new CycleKpiSelectionRequest(quality.getId(), new BigDecimal("40.00")))));
        cycleService.configureEmployees(cycleId, new ConfigureCycleEmployeesRequest(List.of(
                new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()))));
        cycleService.publish(cycleId);

        assignmentId = assignmentRepository.findByCycle_IdOrderByEmployee_IdAsc(cycleId).get(0).getId();
        List<EmployeeKpi> kpis = employeeKpiRepository.findByAssignment_IdOrderByIdAsc(assignmentId);
        firstEmployeeKpiId = kpis.get(0).getId();
        secondEmployeeKpiId = kpis.get(1).getId();

        selfAssessmentService.saveDraft(employee.getUser().getId(), assignmentId,
                new SaveSelfAssessmentDraftRequest(List.of(
                        new SelfAssessmentEntryRequest(firstEmployeeKpiId, new BigDecimal("4"), "Self one."),
                        new SelfAssessmentEntryRequest(secondEmployeeKpiId, new BigDecimal("5"), "Self two."))));
        selfAssessmentService.submit(employee.getUser().getId(), assignmentId);

        managerAssessmentService.saveDraft(manager.getUser().getId(), assignmentId,
                new SaveManagerAssessmentDraftRequest(List.of(
                        new ManagerAssessmentEntryRequest(firstEmployeeKpiId, new BigDecimal("4"), "Manager one."),
                        new ManagerAssessmentEntryRequest(secondEmployeeKpiId, new BigDecimal("4"), "Manager two."))));
        managerAssessmentService.submit(manager.getUser().getId(), assignmentId);
    }

    @Test
    void concurrentSubmissionsProduceExactlyOneSubmission() throws Exception {
        saveDraft(4, 5);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();

        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    try {
                        return hrAssessmentService.submit(hrUser.getId(), assignmentId);
                    } catch (Throwable failure) {
                        return failure;
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> outcomes = List.of(
                    results.get(0).get(20, TimeUnit.SECONDS),
                    results.get(1).get(20, TimeUnit.SECONDS));

            long successes = outcomes.stream().filter(HrAssessmentStatusResponse.class::isInstance).count();
            long conflicts = outcomes.stream()
                    .filter(HrApiException.class::isInstance)
                    .map(HrApiException.class::cast)
                    .filter(failure -> failure.getType() == HrErrorType.CONFLICT)
                    .count();

            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
            assertCompleted();
            assertThat(hrAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId)).hasSize(2);

            assertThat(resultRepository.count()).isEqualTo(1);
            PerformanceResult result = resultRepository.findByAssignment_Id(assignmentId).orElseThrow();
            assertThat(result.getOverallScore()).isEqualByComparingTo("4.20");
            assertThat(result.getCalculationMode()).isEqualTo(CalculationMode.WEIGHTED);
            assertThat(result.getGrade()).isEqualTo(PerformanceGrade.EXCELLENT);
            assertThat(result.getStatus()).isEqualTo(ResultStatus.DRAFT);
            assertThat(result.getPublishedAt()).isNull();
            assertThat(resultItemRepository.findByResult_Id(result.getId())).hasSize(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDraftSavesDoNotCreateDuplicateRows() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();

        try {
            for (int rating : List.of(3, 5)) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    try {
                        return hrAssessmentService.saveDraft(hrUser.getId(), assignmentId,
                                draft(firstEmployeeKpiId, rating, "Concurrent."));
                    } catch (Throwable failure) {
                        return failure;
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> outcomes = List.of(
                    results.get(0).get(20, TimeUnit.SECONDS),
                    results.get(1).get(20, TimeUnit.SECONDS));

            assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome)
                    .isInstanceOf(HrAssessmentResponse.class));
            assertThat(hrAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId)).hasSize(1);
            assertThat(hrAssessmentRepository.count()).isEqualTo(1);
            EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
            assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.HR_REVIEW_PENDING);
            assertThat(assignment.getHrFinalizedAt()).isNull();
            assertThat(resultRepository.count()).isZero();
            assertThat(resultItemRepository.count()).isZero();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void waitingDraftRejectsAfterTheHrSubmissionCommits() throws Exception {
        saveDraft(4, 5);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch submissionHeld = new CountDownLatch(1);
        CountDownLatch releaseSubmission = new CountDownLatch(1);
        CountDownLatch draftStarted = new CountDownLatch(1);

        try {
            Future<?> submitter = executor.submit(() -> transaction.executeWithoutResult(status -> {
                hrAssessmentService.submit(hrUser.getId(), assignmentId);
                submissionHeld.countDown();
                await(releaseSubmission);
            }));

            assertThat(submissionHeld.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Object> draft = executor.submit(() -> {
                draftStarted.countDown();
                try {
                    return hrAssessmentService.saveDraft(hrUser.getId(), assignmentId,
                            draft(firstEmployeeKpiId, 1, "Too late."));
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertThat(draftStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> draft.get(750, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseSubmission.countDown();
            submitter.get(20, TimeUnit.SECONDS);

            Object outcome = draft.get(20, TimeUnit.SECONDS);
            assertThat(outcome).isInstanceOf(HrApiException.class);
            HrApiException failure = (HrApiException) outcome;
            assertThat(failure.getType()).isEqualTo(HrErrorType.CONFLICT);
            assertThat(failure).hasMessage("The HR assessment has already been submitted.");

            HrAssessmentResponse detail = hrAssessmentService.get(hrUser.getId(), assignmentId);
            assertThat(hrRatingOf(detail, firstEmployeeKpiId)).isEqualTo(4);
            assertThat(hrCommentOf(detail, firstEmployeeKpiId)).isEqualTo("Draft.");
            assertCompleted();
            assertThat(resultRepository.count()).isEqualTo(1);
            PerformanceResult result = resultRepository.findByAssignment_Id(assignmentId).orElseThrow();
            assertThat(resultItemRepository.findByResult_Id(result.getId())).hasSize(2);
        } finally {
            releaseSubmission.countDown();
            executor.shutdownNow();
        }
    }

    // ------------------------------------------------- deterministic locking

    /**
     * Deterministic proof that an HR draft save takes the assignment row lock:
     * a separate transaction holds it first, so the save is guaranteed to
     * block. If the write path stopped locking, this test would fail rather
     * than pass by luck.
     */
    @Test
    void draftSaveBlocksWhileAnotherTransactionHoldsTheAssignmentLock() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch draftStarted = new CountDownLatch(1);

        try {
            Future<?> holder = executor.submit(() -> transaction.executeWithoutResult(status -> {
                assignmentRepository.findByIdForUpdate(assignmentId).orElseThrow();
                lockHeld.countDown();
                await(releaseLock);
            }));

            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Object> draft = executor.submit(() -> {
                draftStarted.countDown();
                return saveDraftOrFailure(firstEmployeeKpiId, 2, "Blocked.");
            });
            assertThat(draftStarted.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> draft.get(750, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseLock.countDown();
            holder.get(20, TimeUnit.SECONDS);

            Object outcome = draft.get(20, TimeUnit.SECONDS);
            assertThat(outcome).isInstanceOf(HrAssessmentResponse.class);
            HrAssessmentResponse saved = (HrAssessmentResponse) outcome;
            assertThat(hrRatingOf(saved, firstEmployeeKpiId)).isEqualTo(2);
            assertThat(hrAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId))
                    .hasSize(1);
            EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
            assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.HR_REVIEW_PENDING);
            assertThat(assignment.getHrFinalizedAt()).isNull();
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * The mirror case for submission: it must wait on the same row lock as a
     * draft save, then finalize atomically once the lock is released.
     */
    @Test
    void submissionBlocksWhileAnotherTransactionHoldsTheAssignmentLock() throws Exception {
        saveDraft(4, 5);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch submitStarted = new CountDownLatch(1);

        try {
            Future<?> holder = executor.submit(() -> transaction.executeWithoutResult(status -> {
                assignmentRepository.findByIdForUpdate(assignmentId).orElseThrow();
                lockHeld.countDown();
                await(releaseLock);
            }));

            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Object> submission = executor.submit(() -> {
                submitStarted.countDown();
                try {
                    return hrAssessmentService.submit(hrUser.getId(), assignmentId);
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertThat(submitStarted.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> submission.get(750, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseLock.countDown();
            holder.get(20, TimeUnit.SECONDS);

            Object outcome = submission.get(20, TimeUnit.SECONDS);
            assertThat(outcome).isInstanceOf(HrAssessmentStatusResponse.class);
            assertCompleted();
            assertThat(hrAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId))
                    .hasSize(2);
            assertThat(resultRepository.count()).isEqualTo(1);
            PerformanceResult result = resultRepository.findByAssignment_Id(assignmentId).orElseThrow();
            assertThat(resultItemRepository.findByResult_Id(result.getId())).hasSize(2);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    // ----------------------------------------------------------------- helpers

    private Object saveDraftOrFailure(Long employeeKpiId, int rating, String comment) {
        try {
            return hrAssessmentService.saveDraft(hrUser.getId(), assignmentId,
                    draft(employeeKpiId, rating, comment));
        } catch (Throwable failure) {
            return failure;
        }
    }

    private void saveDraft(int firstRating, int secondRating) {
        hrAssessmentService.saveDraft(hrUser.getId(), assignmentId,
                new SaveHrAssessmentDraftRequest(List.of(
                        new HrAssessmentEntryRequest(firstEmployeeKpiId,
                                new BigDecimal(firstRating), "Draft."),
                        new HrAssessmentEntryRequest(secondEmployeeKpiId,
                                new BigDecimal(secondRating), "Draft."))));
    }

    private static SaveHrAssessmentDraftRequest draft(Long employeeKpiId, int rating, String comment) {
        return new SaveHrAssessmentDraftRequest(List.of(
                new HrAssessmentEntryRequest(employeeKpiId, new BigDecimal(rating), comment)));
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

    private void assertCompleted() {
        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(assignment.getHrFinalizedAt()).isNotNull();
        assertThat(assignment.getResultsPublishedAt()).isNull();
    }

    private Employee profile(Role role, String code, String name) {
        User user = userRepository.saveAndFlush(
                CycleTestData.user(code.toLowerCase() + "@evalora.local", role));
        return employeeRepository.saveAndFlush(CycleTestData.profile(user, code, name));
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
