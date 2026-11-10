
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

@Component
@Profile("prod")
public class ProductionHrBootstrapSeeder implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(ProductionHrBootstrapSeeder.class);

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    public ProductionHrBootstrapSeeder(
            UserRepository userRepository,
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
        String email = trimToNull(
                environment.getProperty("evalora.production.hr.email"));

        String rawPassword =
                environment.getProperty("evalora.production.hr.password");

        String password = (rawPassword == null || rawPassword.isBlank())
                ? null : rawPassword;

        if (email == null && password == null) {
            log.info("Production HR bootstrap is not configured. No account created.");
            return;
        }

        if (email == null || password == null) {
            throw new IllegalStateException(
                    "Production HR bootstrap requires both email and password.");
        }

        if (!PasswordPolicy.isValid(password)) {
            throw new IllegalStateException(
                    "Production HR bootstrap password does not satisfy the password policy. "
                            + PasswordPolicy.MESSAGE);
        }

        if (userRepository.existsByRole(Role.HR)) {
            log.info("An HR account already exists. Production bootstrap skipped.");
            return;
        }

        String normalizedEmail = EmailNormalizer.normalize(email);

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new IllegalStateException(
                    "Production HR bootstrap email is already used by another account.");
        }

        String employeeCode = trimToNull(
                environment.getProperty("evalora.production.hr.employee-code"));

        if (employeeCode == null) {
            employeeCode = "HR-001";
        }

        if (employeeRepository.existsByEmployeeCodeIgnoreCase(employeeCode)) {
            throw new IllegalStateException(
                    "Production HR employee code is already in use.");
        }

        String fullName = trimToNull(
                environment.getProperty("evalora.production.hr.full-name"));

        if (fullName == null) {
            fullName = "HR Administrator";
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

        log.info("Initial production HR account created successfully.");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
