package com.evalora.backend.config;

import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.entity.enums.StaffStatus;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.util.EmailNormalizer;
import com.evalora.backend.util.PasswordPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first HR account for local development.
 *
 * <p>Active <strong>only</strong> under the {@code dev} Spring profile. It reads
 * the credentials from the environment (through the properties below) and never
 * uses a built-in default email or password:</p>
 *
 * <ul>
 *   <li>{@code EVALORA_BOOTSTRAP_HR_EMAIL} (required together with the password)</li>
 *   <li>{@code EVALORA_BOOTSTRAP_HR_PASSWORD} (required together with the email)</li>
 *   <li>{@code EVALORA_BOOTSTRAP_HR_EMPLOYEE_CODE} (optional, default {@code HR-001})</li>
 *   <li>{@code EVALORA_BOOTSTRAP_HR_FULL_NAME} (optional, default {@code HR Administrator})</li>
 * </ul>
 *
 * <p>Idempotent: if any HR account already exists, nothing is created. A
 * partial configuration or a password that breaks the policy fails startup with
 * a safe message. The password is never logged.</p>
 */
@Component
@Profile("dev")
public class BootstrapHrSeeder implements ApplicationRunner {

    static final String EMAIL_PROPERTY = "evalora.bootstrap.hr.email";
    static final String PASSWORD_PROPERTY = "evalora.bootstrap.hr.password";
    static final String CODE_PROPERTY = "evalora.bootstrap.hr.employee-code";
    static final String FULL_NAME_PROPERTY = "evalora.bootstrap.hr.full-name";

    static final String DEFAULT_EMPLOYEE_CODE = "HR-001";
    static final String DEFAULT_FULL_NAME = "HR Administrator";

    private static final Logger log = LoggerFactory.getLogger(BootstrapHrSeeder.class);

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    public BootstrapHrSeeder(UserRepository userRepository,
                             EmployeeRepository employeeRepository,
                             PasswordEncoder passwordEncoder,
                             Environment environment) {
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String email = trimToNull(environment.getProperty(EMAIL_PROPERTY));
        String rawPassword = environment.getProperty(PASSWORD_PROPERTY);
        String password = (rawPassword == null || rawPassword.isBlank()) ? null : rawPassword;

        if (email == null && password == null) {
            log.warn("Dev HR bootstrap is not configured. Set EVALORA_BOOTSTRAP_HR_EMAIL and "
                    + "EVALORA_BOOTSTRAP_HR_PASSWORD to create an initial HR account; no account was created.");
            return;
        }
        if (email == null || password == null) {
            throw new IllegalStateException("Incomplete dev HR bootstrap configuration. Set both "
                    + "EVALORA_BOOTSTRAP_HR_EMAIL and EVALORA_BOOTSTRAP_HR_PASSWORD, or neither. "
                    + "No account was created.");
        }
        if (!PasswordPolicy.isValid(password)) {
            throw new IllegalStateException("EVALORA_BOOTSTRAP_HR_PASSWORD does not satisfy the password policy. "
                    + PasswordPolicy.MESSAGE);
        }

        String normalizedEmail = EmailNormalizer.normalize(email);
        if (userRepository.existsByRole(Role.HR)) {
            log.info("An HR account already exists; the dev HR bootstrap was skipped.");
            return;
        }
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new IllegalStateException("EVALORA_BOOTSTRAP_HR_EMAIL is already used by a non-HR account. "
                    + "No account was created.");
        }

        String employeeCode = trimToNull(environment.getProperty(CODE_PROPERTY));
        if (employeeCode == null) {
            employeeCode = DEFAULT_EMPLOYEE_CODE;
        }
        if (employeeRepository.existsByEmployeeCodeIgnoreCase(employeeCode)) {
            throw new IllegalStateException("EVALORA_BOOTSTRAP_HR_EMPLOYEE_CODE '" + employeeCode
                    + "' is already used by another profile. No account was created.");
        }

        String fullName = trimToNull(environment.getProperty(FULL_NAME_PROPERTY));
        if (fullName == null) {
            fullName = DEFAULT_FULL_NAME;
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(Role.HR);
        user.setEnabled(true);
        user = userRepository.saveAndFlush(user);

        Employee employee = new Employee();
        employee.setUser(user);
        employee.setEmployeeCode(employeeCode);
        employee.setFullName(fullName);
        employee.setDesignation("HR");
        employee.setStatus(StaffStatus.ACTIVE);
        employeeRepository.saveAndFlush(employee);

        log.info("Dev bootstrap HR account created.");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
