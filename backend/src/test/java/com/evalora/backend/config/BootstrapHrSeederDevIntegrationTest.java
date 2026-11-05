package com.evalora.backend.config;

import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.entity.enums.StaffStatus;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dev-profile bootstrap behaviour with a real (H2) database: the seeder runs at
 * startup, creates the HR account and profile, and stays idempotent.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_bootstrap_hr;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "evalora.bootstrap.hr.email=  Bootstrap.HR@Evalora.Local ",
        "evalora.bootstrap.hr.password=Bootstrap@2026",
        "evalora.bootstrap.hr.employee-code=HR-BOOT-1",
        "evalora.bootstrap.hr.full-name=Bootstrap HR"
})
class BootstrapHrSeederDevIntegrationTest {

    private static final String EMAIL = "bootstrap.hr@evalora.local";
    private static final String PASSWORD = "Bootstrap@2026";

    @Autowired
    private BootstrapHrSeeder seeder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void startupCreatesOneHrAccountAndItsProfileWithABcryptHash() {
        List<User> hrUsers = userRepository.findAll().stream()
                .filter(user -> user.getRole() == Role.HR)
                .toList();
        assertThat(hrUsers).hasSize(1);

        User hr = hrUsers.getFirst();
        assertThat(hr.getEmail()).isEqualTo(EMAIL);
        assertThat(hr.isEnabled()).isTrue();
        assertThat(hr.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, hr.getPasswordHash())).isTrue();

        Employee profile = employeeRepository.findByUser_Id(hr.getId()).orElseThrow();
        assertThat(profile.getEmployeeCode()).isEqualTo("HR-BOOT-1");
        assertThat(profile.getFullName()).isEqualTo("Bootstrap HR");
        assertThat(profile.getStatus()).isEqualTo(StaffStatus.ACTIVE);
        assertThat(profile.getManager()).isNull();
    }

    @Test
    void runningTheSeederAgainDoesNotCreateDuplicates() {
        long usersBefore = userRepository.count();
        long employeesBefore = employeeRepository.count();

        seeder.run(null);

        assertThat(userRepository.count()).isEqualTo(usersBefore);
        assertThat(employeeRepository.count()).isEqualTo(employeesBefore);
    }
}
