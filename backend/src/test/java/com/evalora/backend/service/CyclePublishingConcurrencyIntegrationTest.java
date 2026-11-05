package com.evalora.backend.service;

import com.evalora.backend.dto.CycleEmployeeSelectionRequest;
import com.evalora.backend.dto.CycleKpiSelectionRequest;
import com.evalora.backend.dto.ConfigureCycleEmployeesRequest;
import com.evalora.backend.dto.ConfigureCycleKpisRequest;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.KpiMaster;
import com.evalora.backend.entity.PerformanceCycle;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.CycleStatus;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.entity.enums.StaffStatus;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.exception.HrErrorType;
import com.evalora.backend.repository.CycleKpiRepository;
import com.evalora.backend.repository.EmployeeAssignmentRepository;
import com.evalora.backend.repository.EmployeeKpiRepository;
import com.evalora.backend.repository.EmployeeManagerAssignmentRepository;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.KpiMasterRepository;
import com.evalora.backend.repository.PerformanceCycleRepository;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that publication serialises on the cycle row and re-validates staff
 * after taking their row locks.
 *
 * <p>H2 is used only for the Hibernate-generated schema and JPA row locking.
 * PostgreSQL-specific index behaviour is not proven here.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_cycle_concurrency;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000"
})
class CyclePublishingConcurrencyIntegrationTest {

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
    private PlatformTransactionManager transactionManager;

    private Employee manager;
    private Long cycleId;

    @BeforeEach
    void setUp() {
        clearDatabase();

        User hrUser = userRepository.saveAndFlush(
                CycleTestData.user("hr.publish.concurrency@evalora.local", Role.HR));
        manager = profile(Role.MANAGER, "MGR-CON", "Mona Manager");
        Employee employee = profile(Role.EMPLOYEE, "EMP-CON-1", "Eve Employee");
        Employee secondEmployee = profile(Role.EMPLOYEE, "EMP-CON-2", "Eli Employee");

        KpiMaster delivery = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Delivery Quality", "Meets agreed deadlines.", "Delivery"));
        KpiMaster quality = kpiRepository.saveAndFlush(
                CycleTestData.kpi("Quality", "Work meets the quality bar.", "Quality"));

        cycleId = cycleService.create(CycleTestData.request("Annual 2026"), hrUser.getId()).id();
        cycleService.configureKpis(cycleId, new ConfigureCycleKpisRequest(List.of(
                new CycleKpiSelectionRequest(delivery.getId(), new BigDecimal("60.00")),
                new CycleKpiSelectionRequest(quality.getId(), new BigDecimal("40.00")))));
        cycleService.configureEmployees(cycleId, new ConfigureCycleEmployeesRequest(List.of(
                new CycleEmployeeSelectionRequest(employee.getId(), manager.getId()),
                new CycleEmployeeSelectionRequest(secondEmployee.getId(), manager.getId()))));
    }

    @Test
    void twoConcurrentPublishesProduceExactlyOnePublication() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();

        for (int attempt = 0; attempt < 2; attempt++) {
            results.add(executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                    return cycleService.publish(cycleId);
                } catch (Throwable failure) {
                    return failure;
                }
            }));
        }

        start.countDown();
        Object first = results.get(0).get(20, TimeUnit.SECONDS);
        Object second = results.get(1).get(20, TimeUnit.SECONDS);
        executor.shutdownNow();

        List<Object> outcomes = List.of(first, second);
        long successes = outcomes.stream()
                .filter(outcome -> outcome instanceof com.evalora.backend.dto.CycleResponse)
                .count();
        long conflicts = outcomes.stream()
                .filter(HrApiException.class::isInstance)
                .map(HrApiException.class::cast)
                .filter(failure -> failure.getType() == HrErrorType.CONFLICT)
                .count();

        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        PerformanceCycle cycle = cycleRepository.findById(cycleId).orElseThrow();
        assertThat(cycle.getStatus()).isEqualTo(CycleStatus.PUBLISHED);
        assertThat(employeeKpiRepository.count()).isEqualTo(4);
    }

    @Test
    void publishRejectsAManagerDeactivatedWhileItWaitedForTheRowLock() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread publisher = new Thread(() -> {
            try {
                cycleService.publish(cycleId);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "publish-thread");

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            Employee locked = employeeRepository.findByIdForUpdate(manager.getId()).orElseThrow();
            publisher.start();
            sleep(500);
            assertThat(publisher.isAlive()).isTrue();

            locked.setStatus(StaffStatus.INACTIVE);
            locked.getUser().setEnabled(false);
        });

        publisher.join(20_000);

        assertThat(publisher.isAlive()).isFalse();
        assertThat(failure.get())
                .isInstanceOf(HrApiException.class)
                .hasMessage("The selected manager is inactive.");
        assertThat(((HrApiException) failure.get()).getType()).isEqualTo(HrErrorType.CONFLICT);

        PerformanceCycle cycle = cycleRepository.findById(cycleId).orElseThrow();
        assertThat(cycle.getStatus()).isEqualTo(CycleStatus.DRAFT);
        assertThat(employeeKpiRepository.count()).isZero();
        assertThat(assignmentRepository.findByCycle_IdOrderByEmployee_IdAsc(cycleId)).hasSize(2);
    }

    // ----------------------------------------------------------------- helpers

    private Employee profile(Role role, String code, String name) {
        User user = userRepository.saveAndFlush(
                CycleTestData.user(code.toLowerCase() + "@evalora.local", role));
        return employeeRepository.saveAndFlush(CycleTestData.profile(user, code, name));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", ex);
        }
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
