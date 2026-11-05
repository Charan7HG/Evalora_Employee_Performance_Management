package com.evalora.backend.service;

import com.evalora.backend.dto.CreateKpiRequest;
import com.evalora.backend.entity.KpiMaster;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.exception.HrErrorType;
import com.evalora.backend.repository.KpiMasterRepository;
import com.evalora.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Proves that a KPI name collision is reported as a domain conflict, not as a
 * raw database failure, even when the duplicate slips past the service
 * pre-check because another transaction already inserted the name.
 *
 * <p>The two-transaction test holds the first insert uncommitted while the
 * second request runs, so the second one reaches the database constraint. The
 * first test forces the same failure with a spied repository, keeping both
 * paths deterministic instead of timing-dependent.</p>
 *
 * <p>H2 enforces the entity's case-sensitive unique constraint only; the
 * PostgreSQL {@code LOWER(name)} functional index is verified separately
 * against PostgreSQL and is not exercised here.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_kpi_concurrency;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000"
})
class KpiConcurrencyIntegrationTest {

    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(12).encode("KpiConc@2026");

    @Autowired
    private KpiManagementService kpiManagementService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private KpiMasterRepository kpiRepository;

    private User hrUser;

    @BeforeEach
    void setUp() {
        kpiRepository.deleteAll();
        userRepository.deleteAll();
        hrUser = userRepository.saveAndFlush(user("hr.kpi.concurrency@evalora.local", Role.HR));
        Mockito.clearInvocations(kpiRepository);
    }

    @AfterEach
    void tearDown() {
        // Never let a failing answer leak into another test method.
        Mockito.reset(kpiRepository);
    }

    @Test
    void aDatabaseUniquenessViolationIsReportedAsAConflict() {
        doThrow(new DataIntegrityViolationException("simulated duplicate key"))
                .when(kpiRepository).saveAndFlush(any());

        assertThatThrownBy(() -> kpiManagementService.create(
                new CreateKpiRequest("Delivery Quality", null, null, null), hrUser.getId()))
                .isInstanceOf(HrApiException.class)
                .hasMessage("A KPI with this name already exists.")
                .extracting(failure -> ((HrApiException) failure).getType())
                .isEqualTo(HrErrorType.CONFLICT);
    }

    @Test
    void concurrentDuplicateCreationLetsExactlyOneSucceed() throws Exception {
        AtomicReference<Long> seededId = new AtomicReference<>();
        AtomicReference<Throwable> duplicateFailure = new AtomicReference<>();
        AtomicReference<Thread> duplicateWriter = new AtomicReference<>();

        // Hold an uncommitted KPI row: the racing request cannot see it during
        // its pre-check, so its only protection is the database constraint.
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            KpiMaster seed = new KpiMaster();
            seed.setName("Concurrent KPI");
            seed.setActive(true);
            seed.setCreatedBy(userRepository.getReferenceById(hrUser.getId()));
            seededId.set(kpiRepository.saveAndFlush(seed).getId());

            Thread racer = new Thread(() -> {
                try {
                    kpiManagementService.create(
                            new CreateKpiRequest("Concurrent KPI", null, null, null), hrUser.getId());
                } catch (Throwable failure) {
                    duplicateFailure.set(failure);
                }
            }, "kpi-create-duplicate");
            duplicateWriter.set(racer);
            racer.start();

            // Give the racer time to pass its pre-check and reach the insert,
            // where it waits for this transaction to finish.
            sleep(500);
        });

        duplicateWriter.get().join(20_000);

        assertThat(duplicateWriter.get().isAlive()).isFalse();
        assertThat(seededId.get()).isNotNull();

        // The racer reached saveAndFlush: the seed row and the racing attempt.
        // If it had been stopped by the pre-check, only the seed would appear.
        verify(kpiRepository, times(2)).saveAndFlush(any(KpiMaster.class));

        assertThat(duplicateFailure.get())
                .isInstanceOf(HrApiException.class)
                .hasMessage("A KPI with this name already exists.");
        assertThat(((HrApiException) duplicateFailure.get()).getType()).isEqualTo(HrErrorType.CONFLICT);

        // Exactly one row survives, and it is the first transaction's row.
        assertThat(kpiRepository.count()).isEqualTo(1);
        assertThat(kpiRepository.findById(seededId.get()).orElseThrow().getName())
                .isEqualTo("Concurrent KPI");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", ex);
        }
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
