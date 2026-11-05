package com.evalora.backend.service;

import com.evalora.backend.dto.CreateStaffRequest;
import com.evalora.backend.dto.StaffResponse;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.entity.enums.StaffStatus;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.exception.HrErrorType;
import com.evalora.backend.repository.EmployeeManagerAssignmentRepository;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Proves that employee creation and manager deactivation serialise on the same
 * manager row.
 *
 * <p>Both operations take a pessimistic write lock on the manager's
 * {@code employees} row before validating/counting, and the lock is held until
 * the transaction commits. The tests manipulate the two threads directly so
 * each interleaving is deterministic rather than timing-dependent.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_hr_locking;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000"
})
class ManagerAssignmentConcurrencyIntegrationTest {

    private static final String NEW_HIRE_PASSWORD = "NewHire@2026";

    @Autowired
    private EmployeeManagementService employeeManagementService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeManagerAssignmentRepository assignmentRepository;

    @MockitoSpyBean
    private EmployeeRepository employeeRepository;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User hrUser;
    private User managerUser;
    private Employee manager;

    private final CountDownLatch creationReachedPasswordHashing = new CountDownLatch(1);
    private final CountDownLatch releaseCreation = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        assignmentRepository.deleteAll();
        employeeRepository.deleteAll();
        userRepository.deleteAll();

        hrUser = userRepository.saveAndFlush(user("hr.locking@evalora.local", Role.HR));
        managerUser = userRepository.saveAndFlush(user("manager.locking@evalora.local", Role.MANAGER));
        manager = employeeRepository.saveAndFlush(
                profile(managerUser, "MGR-LOCK", "Locking Manager"));

        Mockito.clearInvocations(employeeRepository);
    }

    @AfterEach
    void tearDown() {
        // Do not let the blocking answers leak into another test method.
        Mockito.reset(employeeRepository, passwordEncoder);
    }

    @Test
    void employeeCreationLocksTheSelectedManagerRowBeforeValidatingIt() {
        StaffResponse created = employeeManagementService.createEmployee(
                newHire("EMP-LOCK-1", "new.hire.lock@evalora.local"), hrUser.getId());

        assertThat(created.managerId()).isEqualTo(manager.getId());

        // Creation must take the same pessimistic lock that manager deactivation
        // and reassignment take, not an ordinary unlocked lookup.
        verify(employeeRepository, times(1)).findByIdForUpdate(manager.getId());
        assertThat(assignmentRepository.count()).isEqualTo(1);
        assertThat(assignmentRepository.findByEmployee_IdAndEffectiveToIsNull(created.id())).hasSize(1);
    }

    @Test
    void creationRejectsAManagerDeactivatedWhileCreationWaitedForTheLock() throws Exception {
        String email = "blocked.hire@evalora.local";
        AtomicReference<Throwable> creationFailure = new AtomicReference<>();
        AtomicBoolean creatorWasBlocked = new AtomicBoolean(false);

        Thread creator = new Thread(() -> {
            try {
                employeeManagementService.createEmployee(
                        newHire("EMP-LOCK-2", email), hrUser.getId());
            } catch (Throwable failure) {
                creationFailure.set(failure);
            }
        }, "create-thread");

        // Hold the manager row exactly like a deactivation transaction would,
        // then let creation try to lock it.
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            Employee locked = employeeRepository.findByIdForUpdate(manager.getId()).orElseThrow();
            creator.start();
            sleep(400);
            creatorWasBlocked.set(creator.isAlive());

            locked.setStatus(StaffStatus.INACTIVE);
            locked.getUser().setEnabled(false);
        });

        creator.join(20_000);

        assertThat(creator.isAlive()).isFalse();
        assertThat(creatorWasBlocked).isTrue();
        assertThat(creationFailure.get())
                .isInstanceOf(HrApiException.class)
                .hasMessageContaining("inactive");
        assertThat(((HrApiException) creationFailure.get()).getType()).isEqualTo(HrErrorType.CONFLICT);

        // A rejected creation must leave neither an account nor a profile behind.
        assertThat(userRepository.findByEmailIgnoreCase(email)).isEmpty();
        assertThat(employeeRepository.findById(manager.getId()).orElseThrow().getStatus())
                .isEqualTo(StaffStatus.INACTIVE);
        assertThat(employeeRepository.count()).isEqualTo(1);
    }

    @Test
    void managerDeactivationWaitsForInFlightCreationAndThenConflicts() throws Exception {
        // Creation locks the manager row before hashing the initial password.
        // Pausing hashing therefore pauses the transaction while it still holds
        // the manager lock, which is exactly the in-flight window to test.
        doAnswer(invocation -> {
            creationReachedPasswordHashing.countDown();
            releaseCreation.await(20, TimeUnit.SECONDS);
            return "$2a$12$lockingtestonlyplaceholderhashvalue000000000000000000000000";
        }).when(passwordEncoder).encode(any(String.class));

        AtomicReference<StaffResponse> created = new AtomicReference<>();
        AtomicReference<Throwable> creationFailure = new AtomicReference<>();
        AtomicReference<Throwable> deactivationFailure = new AtomicReference<>();

        Thread creator = new Thread(() -> {
            try {
                created.set(employeeManagementService.createEmployee(
                        newHire("EMP-LOCK-3", "racing.hire@evalora.local"), hrUser.getId()));
            } catch (Throwable failure) {
                creationFailure.set(failure);
            }
        }, "create-thread");
        creator.start();

        // Creation now holds the manager row lock and is paused before commit.
        assertThat(creationReachedPasswordHashing.await(20, TimeUnit.SECONDS)).isTrue();

        Thread deactivator = new Thread(() -> {
            try {
                employeeManagementService.deactivateManager(manager.getId());
            } catch (Throwable failure) {
                deactivationFailure.set(failure);
            }
        }, "deactivate-thread");
        deactivator.start();

        sleep(500);
        boolean deactivatorWaited = deactivator.isAlive();

        releaseCreation.countDown();
        creator.join(20_000);
        deactivator.join(20_000);

        assertThat(deactivatorWaited).isTrue();
        assertThat(creationFailure.get()).isNull();
        assertThat(created.get().managerId()).isEqualTo(manager.getId());

        // Deactivation ran after the creation commit, so it must see the new
        // active report and refuse to disable the manager.
        assertThat(deactivationFailure.get())
                .isInstanceOf(HrApiException.class)
                .hasMessageContaining("Reassign");
        assertThat(((HrApiException) deactivationFailure.get()).getType()).isEqualTo(HrErrorType.CONFLICT);

        Employee reloadedManager = employeeRepository.findById(manager.getId()).orElseThrow();
        assertThat(reloadedManager.getStatus()).isEqualTo(StaffStatus.ACTIVE);
        assertThat(userRepository.findById(managerUser.getId()).orElseThrow().isEnabled()).isTrue();
        assertThat(assignmentRepository.count()).isEqualTo(1);
    }

    // ----------------------------------------------------------------- helpers

    private CreateStaffRequest newHire(String employeeCode, String email) {
        return new CreateStaffRequest(
                "New", "Hire", email, employeeCode,
                "Engineering", "Developer", LocalDate.of(2026, 2, 2),
                NEW_HIRE_PASSWORD, manager.getId());
    }

    private User user(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode("Locking@2026"));
        user.setRole(role);
        user.setEnabled(true);
        return user;
    }

    private Employee profile(User user, String employeeCode, String fullName) {
        Employee profile = new Employee();
        profile.setUser(user);
        profile.setEmployeeCode(employeeCode);
        profile.setFullName(fullName);
        profile.setStatus(StaffStatus.ACTIVE);
        return profile;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", ex);
        }
    }
}
