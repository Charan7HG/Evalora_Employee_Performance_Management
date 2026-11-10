package com.evalora.backend.migration;

import com.evalora.backend.dto.CreateStaffRequest;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.entity.enums.StaffStatus;
import com.evalora.backend.exception.HrApiException;
import com.evalora.backend.exception.HrErrorType;
import com.evalora.backend.repository.ActivationTokenRepository;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.EmployeeManagerAssignmentRepository;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.service.EmployeeManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Covers the V3 case-insensitive employee-code index.
 *
 * <p>H2 cannot execute the migration's PL/pgSQL {@code DO} block or guarantee
 * that a functional unique index behaves like PostgreSQL's, so the tests split
 * the concerns: the service-level cases run against the real service and H2,
 * the duplicate-detection predicate is extracted from the migration and
 * executed against rows inserted directly (bypassing the service), and the
 * migration text is checked for ordering and for the absence of data-changing
 * statements. The PostgreSQL {@code RAISE} and the functional index itself are
 * not executed here.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_hr_code_index;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class EmployeeCodeUniquenessMigrationTest {

    private static final String V1_FILE = "V1__init_schema.sql";
    private static final String V2_FILE = "V2__employee_manager_assignments.sql";
    private static final String V3_FILE = "V3__case_insensitive_employee_code_uniqueness.sql";

    private static final String V1_SHA256 =
            "3F099319CDEBFFBBD4B547A4919A43F07331FFD22F26BE580E66CCEECFB25478";
    private static final String V2_SHA256 =
            "1DB5452BC6BE70CD5A8702AB9A312CC81564946E3AA3F4A092EF67438E4E00D6";

    private static final String PASSWORD = "CodeCase@2026";

    @Autowired
    private EmployeeManagementService employeeManagementService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeeManagerAssignmentRepository managerAssignmentRepository;

    @Autowired
    private ActivationTokenRepository activationTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User hrUser;
    private Long managerId;

    @BeforeEach
    void setUp() {
        managerAssignmentRepository.deleteAll();
        employeeRepository.deleteAll();
        activationTokenRepository.deleteAll();
        userRepository.deleteAll();
        hrUser = userRepository.saveAndFlush(user("hr.code@evalora.local", Role.HR));
        managerId = saveActiveManagerFixture();
    }

    @Test
    void employeeCodesDifferingOnlyByCaseCannotCoexistThroughTheService() {
        employeeManagementService.createEmployee(
                newRequest("EMP001", "first.code@evalora.local", managerId), hrUser.getId());

        HrApiException conflict = catchThrowableOfType(
                () -> employeeManagementService.createEmployee(
                        newRequest("emp001", "second.code@evalora.local", managerId), hrUser.getId()),
                HrApiException.class);

        assertThat(conflict)
                .hasMessageContaining("An employee with this code already exists.");
        assertThat(conflict.getType()).isEqualTo(HrErrorType.CONFLICT);
        assertThat(employeeRepository.count()).isEqualTo(2);
        assertThat(userRepository.findByEmailIgnoreCase("second.code@evalora.local")).isEmpty();
    }

    @Test
    void distinctEmployeeCodesCanCoexist() {
        employeeManagementService.createEmployee(
                newRequest("EMP001", "alpha.code@evalora.local", managerId), hrUser.getId());
        employeeManagementService.createEmployee(
                newRequest("EMP002", "beta.code@evalora.local", managerId), hrUser.getId());

        assertThat(employeeRepository.count()).isEqualTo(3);
    }

    @Test
    void duplicateCheckFindsExistingCaseInsensitiveDuplicates() {
        saveProfileDirectly("EMP001", "dup.one@evalora.local");
        saveProfileDirectly("emp001", "dup.two@evalora.local");

        List<Map<String, Object>> duplicates = jdbcTemplate.queryForList(
                "select normalized_code, duplicate_count from (" + duplicateCheckSql() + ") d");

        assertThat(duplicates).hasSize(1);
        assertThat(duplicates.get(0).get("normalized_code")).isEqualTo("emp001");
        assertThat(((Number) duplicates.get(0).get("duplicate_count")).longValue()).isEqualTo(2);
    }

    @Test
    void duplicateCheckFindsNothingWhenCodesAreDistinct() {
        saveProfileDirectly("EMP001", "distinct.one@evalora.local");
        saveProfileDirectly("EMP002", "distinct.two@evalora.local");

        List<Map<String, Object>> duplicates = jdbcTemplate.queryForList(
                "select normalized_code, duplicate_count from (" + duplicateCheckSql() + ") d");

        assertThat(duplicates).isEmpty();
    }

    @Test
    void migrationChecksForDuplicatesBeforeCreatingTheFunctionalIndexAndNeverChangesData() {
        String migration = migrationSql(V3_FILE);

        assertThat(migration)
                .contains("CREATE UNIQUE INDEX ux_employees_code_lower")
                .contains("ON employees (LOWER(employee_code));")
                .contains("RAISE EXCEPTION")
                .contains("HAVING count(*) > 1")
                .contains("-- duplicate-check-start")
                .contains("-- duplicate-check-end");

        assertThat(migration.indexOf("RAISE EXCEPTION"))
                .as("the duplicate guard must run before the index is created")
                .isLessThan(migration.indexOf("CREATE UNIQUE INDEX"));

        String executableSql = stripLineComments(migration).toUpperCase(Locale.ROOT);
        assertThat(executableSql)
                .doesNotContain("DELETE FROM")
                .doesNotContain("UPDATE EMPLOYEES")
                .doesNotContain("ALTER TABLE")
                .doesNotContain("DROP TABLE")
                .doesNotContain("DROP INDEX")
                .doesNotContain("TRUNCATE")
                .doesNotContain("RENAME TABLE");

        // The V1 constraint stays in place; V3 only supplements it.
        assertThat(migrationSql(V1_FILE)).contains("CONSTRAINT uq_employees_code UNIQUE (employee_code)");
    }

    @Test
    void v1AndV2MigrationsAreByteForByteUnchanged() {
        assertThat(sha256(V1_FILE)).isEqualTo(V1_SHA256);
        assertThat(sha256(V2_FILE)).isEqualTo(V2_SHA256);
    }

    // ----------------------------------------------------------------- helpers

    private CreateStaffRequest newRequest(String employeeCode, String email, Long managerId) {
        return new CreateStaffRequest(
                "Case", "Code", email, employeeCode,
                "Engineering", "Developer", LocalDate.of(2026, 3, 2), managerId);
    }

    private void saveProfileDirectly(String employeeCode, String email) {
        User user = userRepository.saveAndFlush(user(email, Role.EMPLOYEE));
        Employee profile = new Employee();
        profile.setUser(user);
        profile.setEmployeeCode(employeeCode);
        profile.setFullName("Direct " + employeeCode);
        profile.setStatus(StaffStatus.ACTIVE);
        employeeRepository.saveAndFlush(profile);
    }

    private Long saveActiveManagerFixture() {
        User managerUser = userRepository.saveAndFlush(
                user("manager.code@evalora.local", Role.MANAGER));
        Employee manager = new Employee();
        manager.setUser(managerUser);
        manager.setEmployeeCode("MGR-CODE");
        manager.setFullName("Case Code");
        manager.setDepartment("Engineering");
        manager.setDesignation("Developer");
        manager.setJoiningDate(LocalDate.of(2026, 3, 2));
        manager.setStatus(StaffStatus.ACTIVE);
        return employeeRepository.saveAndFlush(manager).getId();
    }

    private User user(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setEnabled(true);
        return user;
    }

    private static String duplicateCheckSql() {
        String migration = migrationSql(V3_FILE);
        String startMarker = "-- duplicate-check-start";
        String endMarker = "-- duplicate-check-end";
        int start = migration.indexOf(startMarker);
        int end = migration.indexOf(endMarker);
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalStateException("Duplicate-check markers are missing from " + V3_FILE);
        }
        return migration.substring(start + startMarker.length(), end).trim();
    }

    private static String stripLineComments(String sql) {
        StringBuilder withoutComments = new StringBuilder();
        for (String line : sql.split("\\R")) {
            if (!line.trim().startsWith("--")) {
                withoutComments.append(line).append('\n');
            }
        }
        return withoutComments.toString();
    }

    private static String migrationSql(String fileName) {
        try {
            return Files.readString(migrationPath(fileName), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read " + fileName, ex);
        }
    }

    private static String sha256(String fileName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(migrationPath(fileName)));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read " + fileName, ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static Path migrationPath(String fileName) {
        List<Path> candidates = List.of(
                Path.of("..", "database", "migrations", fileName),
                Path.of("database", "migrations", fileName));
        return candidates.stream()
                .filter(Files::exists)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot locate database/migrations/" + fileName));
    }
}
