package com.evalora.backend.service;

import com.evalora.backend.dto.ConfigureCycleEmployeesRequest;
import com.evalora.backend.dto.ConfigureCycleKpisRequest;
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
import com.evalora.backend.entity.SelfAssessment;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.AssignmentStatus;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.exception.HrErrorType;
import com.evalora.backend.repository.CycleKpiRepository;
import com.evalora.backend.repository.EmployeeAssignmentRepository;
import com.evalora.backend.repository.EmployeeKpiRepository;
import com.evalora.backend.repository.EmployeeManagerAssignmentRepository;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.KpiMasterRepository;
import com.evalora.backend.repository.PerformanceCycleRepository;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Proves that draft saving and submission serialise on the assignment row.
 *
 * <p>H2 is used only for the Hibernate-generated schema and JPA row locking.
 * PostgreSQL-specific lock semantics are not proven here.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_self_assessment_concurrency;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000"
})
class SelfAssessmentConcurrencyIntegrationTest {

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
    private KpiMasterRepository kpiRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Employee employee;
    private Long assignmentId;
    private Long firstEmployeeKpiId;

    @BeforeEach
    void setUp() {
        clearDatabase();

        User hrUser = userRepository.saveAndFlush(
                CycleTestData.user("hr.self.concurrency@evalora.local", Role.HR));
        Employee manager = profile(Role.MANAGER, "MGR-SELF-CON", "Mona Manager");
        employee = profile(Role.EMPLOYEE, "EMP-SELF-CON", "Eve Employee");

        KpiMaster delivery = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Delivery Quality", "Meets agreed deadlines.", "Delivery"));
        KpiMaster quality = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Quality", "Work meets the quality bar.", "Quality"));

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
        saveDraft(4, 5);
    }

    @Test
    void twoConcurrentSubmissionsProduceExactlyOneSubmission() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();

        for (int attempt = 0; attempt < 2; attempt++) {
            results.add(executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                    return selfAssessmentService.submit(employee.getUser().getId(), assignmentId);
                } catch (Throwable failure) {
                    return failure;
                }
            }));
        }

        start.countDown();
        List<Object> outcomes = List.of(
                results.get(0).get(20, TimeUnit.SECONDS),
                results.get(1).get(20, TimeUnit.SECONDS));
        executor.shutdownNow();

        long successes = outcomes.stream()
                .filter(SelfAssessmentStatusResponse.class::isInstance)
                .count();
        long conflicts = outcomes.stream()
                .filter(HrApiException.class::isInstance)
                .map(HrApiException.class::cast)
                .filter(failure -> failure.getType() == HrErrorType.CONFLICT)
                .count();

        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.MANAGER_REVIEW_PENDING);
        assertThat(assignment.getSelfSubmittedAt()).isNotNull();
        assertThat(selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId)).hasSize(2);
    }

    @Test
    void concurrentDraftSavesDoNotCreateDuplicateRows() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();

        for (int rating : List.of(3, 4)) {
            results.add(executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                    return selfAssessmentService.saveDraft(employee.getUser().getId(), assignmentId,
                            new SaveSelfAssessmentDraftRequest(List.of(
                                    new SelfAssessmentEntryRequest(firstEmployeeKpiId,
                                            new BigDecimal(rating), "Concurrent"))));
                } catch (Throwable failure) {
                    return failure;
                }
            }));
        }

        start.countDown();
        List<Object> outcomes = List.of(
                results.get(0).get(20, TimeUnit.SECONDS),
                results.get(1).get(20, TimeUnit.SECONDS));
        executor.shutdownNow();

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isNotInstanceOf(Throwable.class));
        assertThat(selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId)).hasSize(2);
        assertThat(selfAssessmentRepository.count()).isEqualTo(2);

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.SELF_ASSESSMENT_PENDING);
    }

    @Test
    void submitRacingADraftSaveLeavesAConsistentAssignment() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<Object> submission = executor.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            try {
                return selfAssessmentService.submit(employee.getUser().getId(), assignmentId);
            } catch (Throwable failure) {
                return failure;
            }
        });
        Future<Object> draft = executor.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            try {
                return selfAssessmentService.saveDraft(employee.getUser().getId(), assignmentId,
                        new SaveSelfAssessmentDraftRequest(List.of(
                                new SelfAssessmentEntryRequest(firstEmployeeKpiId,
                                        new BigDecimal("2"), "Racing"))));
            } catch (Throwable failure) {
                return failure;
            }
        });

        start.countDown();
        Object submissionOutcome = submission.get(20, TimeUnit.SECONDS);
        Object draftOutcome = draft.get(20, TimeUnit.SECONDS);
        executor.shutdownNow();

        // The submission cannot fail: the draft was already complete.
        assertThat(submissionOutcome).isInstanceOf(SelfAssessmentStatusResponse.class);
        // The draft either landed before the submission or lost to it.
        if (draftOutcome instanceof HrApiException failure) {
            assertThat(failure.getType()).isEqualTo(HrErrorType.CONFLICT);
        }

        EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.MANAGER_REVIEW_PENDING);
        assertThat(assignment.getSelfSubmittedAt()).isNotNull();
        assertThat(selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId)).hasSize(2);
    }

    // ------------------------------------------------- deterministic locking

    /**
     * Deterministic proof of serialisation, without relying on two racing
     * threads happening to overlap: a separate transaction takes the
     * pessimistic lock on the assignment first, so the draft save is
     * guaranteed to be blocked until that transaction commits. If the write
     * path ever stopped taking the lock, this test would fail instead of
     * passing by luck.
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
                assignmentRepository.findByIdAndEmployee_IdForUpdate(assignmentId, employee.getId())
                        .orElseThrow();
                lockHeld.countDown();
                await(releaseLock);
            }));

            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Object> draft = executor.submit(() -> {
                draftStarted.countDown();
                return saveDraftOrFailure(firstEmployeeKpiId, 1, "Blocked");
            });
            assertThat(draftStarted.await(10, TimeUnit.SECONDS)).isTrue();

            // The row lock is provably held, so a write cannot complete.
            assertThatThrownBy(() -> draft.get(750, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseLock.countDown();
            holder.get(20, TimeUnit.SECONDS);

            Object outcome = draft.get(20, TimeUnit.SECONDS);
            assertThat(outcome).isInstanceOf(SelfAssessmentResponse.class);

            SelfAssessmentResponse saved = (SelfAssessmentResponse) outcome;
            assertThat(ratingOf(saved, firstEmployeeKpiId)).isEqualTo(1);
            assertThat(selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId)).hasSize(2);
            assertThat(assignmentRepository.findById(assignmentId).orElseThrow().getStatus())
                    .isEqualTo(AssignmentStatus.SELF_ASSESSMENT_PENDING);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * The mirror case: a competing transaction holds the lock and moves the
     * assignment to manager review before committing. The draft save that was
     * waiting on the lock must observe the committed status and refuse the
     * write, leaving the submitted values untouched.
     *
     * <p>The submission is simulated inside the locked transaction only to
     * control the interleaving deterministically; the real submit transition
     * is covered by the API and integrity tests.</p>
     */
    @Test
    void draftSaveRejectsTheWriteWhenAConcurrentSubmissionCommitsFirst() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch draftStarted = new CountDownLatch(1);

        OffsetDateTime submittedAt = OffsetDateTime.now(ZoneOffset.UTC);

        try {
            Future<?> submitter = executor.submit(() -> transaction.executeWithoutResult(status -> {
                EmployeeAssignment assignment = assignmentRepository
                        .findByIdAndEmployee_IdForUpdate(assignmentId, employee.getId())
                        .orElseThrow();
                assignment.setStatus(AssignmentStatus.MANAGER_REVIEW_PENDING);
                assignment.setSelfSubmittedAt(submittedAt);
                assignmentRepository.saveAndFlush(assignment);
                lockHeld.countDown();
                await(releaseLock);
            }));

            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Object> draft = executor.submit(() -> {
                draftStarted.countDown();
                return saveDraftOrFailure(firstEmployeeKpiId, 1, "Too late");
            });
            assertThat(draftStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> draft.get(750, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseLock.countDown();
            submitter.get(20, TimeUnit.SECONDS);

            Object outcome = draft.get(20, TimeUnit.SECONDS);
            assertThat(outcome).isInstanceOf(HrApiException.class);
            HrApiException failure = (HrApiException) outcome;
            assertThat(failure.getType()).isEqualTo(HrErrorType.CONFLICT);
            assertThat(failure).hasMessage("The self-assessment has already been submitted.");

            List<SelfAssessment> rows =
                    selfAssessmentRepository.findByEmployeeKpi_Assignment_Id(assignmentId);
            assertThat(rows).hasSize(2);
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.getComment()).isEqualTo("Draft");
                assertThat(row.getRating()).isIn((short) 4, (short) 5);
            });

            EmployeeAssignment assignment = assignmentRepository.findById(assignmentId).orElseThrow();
            assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.MANAGER_REVIEW_PENDING);
            assertThat(assignment.getSelfSubmittedAt()).isNotNull();
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    // ----------------------------------------------------------------- helpers

    private Object saveDraftOrFailure(Long employeeKpiId, int rating, String comment) {
        try {
            return selfAssessmentService.saveDraft(employee.getUser().getId(), assignmentId,
                    new SaveSelfAssessmentDraftRequest(List.of(
                            new SelfAssessmentEntryRequest(employeeKpiId,
                                    new BigDecimal(rating), comment))));
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static Integer ratingOf(SelfAssessmentResponse response, Long employeeKpiId) {
        return response.kpis().stream()
                .filter(kpi -> kpi.employeeKpiId().equals(employeeKpiId))
                .findFirst().orElseThrow().rating();
    }

    /** Latched wait helper: interrupts fail the test instead of hanging it. */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the held lock to be released.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private void saveDraft(int firstRating, int secondRating) {
        List<EmployeeKpi> kpis = employeeKpiRepository.findByAssignment_IdOrderByIdAsc(assignmentId);
        selfAssessmentService.saveDraft(employee.getUser().getId(), assignmentId,
                new SaveSelfAssessmentDraftRequest(List.of(
                        new SelfAssessmentEntryRequest(kpis.get(0).getId(),
                                new BigDecimal(firstRating), "Draft"),
                        new SelfAssessmentEntryRequest(kpis.get(1).getId(),
                                new BigDecimal(secondRating), "Draft"))));
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
