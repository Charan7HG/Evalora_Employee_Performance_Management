package com.evalora.backend.service;

import com.evalora.backend.dto.CreateStaffRequest;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Proves that the user row and the profile are one unit of work: when the
 * profile write fails after the account has been inserted, the account insert
 * is rolled back too.
 *
 * <p>The employee repository is spied only in this context, so the failure is
 * injected without touching production code or any other test.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_hr_rollback;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class EmployeeCreationRollbackIntegrationTest {

    private static final String EMAIL = "rollback.employee@evalora.local";
    private static final String PASSWORD = "Rollback@2026";

    @Autowired
    private EmployeeManagementService employeeManagementService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoSpyBean
    private EmployeeRepository employeeRepository;

    private User hrUser;

    @BeforeEach
    void setUp() {
        employeeRepository.deleteAll();
        userRepository.deleteAll();

        hrUser = new User();
        hrUser.setEmail("hr@evalora.local");
        hrUser.setPasswordHash(passwordEncoder.encode(PASSWORD));
        hrUser.setRole(Role.HR);
        hrUser.setEnabled(true);
        hrUser = userRepository.saveAndFlush(hrUser);
    }

    @Test
    void accountInsertIsRolledBackWhenTheProfileCannotBeSaved() {
        doThrow(new DataIntegrityViolationException("simulated profile failure"))
                .when(employeeRepository).saveAndFlush(any(Employee.class));

        CreateStaffRequest request = new CreateStaffRequest(
                "Roll", "Back", EMAIL, "EMP-ROLLBACK",
                "Engineering", "Developer", LocalDate.of(2026, 1, 5), PASSWORD, null);

        assertThatThrownBy(() -> employeeManagementService.createEmployee(request, hrUser.getId()))
                .isInstanceOf(HrApiException.class);

        // The insert that succeeded before the failure must not be visible.
        assertThat(userRepository.findByEmailIgnoreCase(EMAIL)).isEmpty();
        assertThat(userRepository.count()).isEqualTo(1); // the HR account only
        assertThat(employeeRepository.findByUser_Id(hrUser.getId())).isEmpty();
    }
}
