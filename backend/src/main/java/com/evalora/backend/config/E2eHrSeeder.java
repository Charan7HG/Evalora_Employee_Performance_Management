package com.evalora.backend.config;

import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.entity.enums.StaffStatus;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.util.EmailNormalizer;
import com.evalora.backend.util.PasswordPolicy;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("e2e-bootstrap & !dev & !prod")
public class E2eHrSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    public E2eHrSeeder(UserRepository userRepository,
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
        String email = trimToNull(environment.getProperty("evalora.e2e.bootstrap.email"));
        String configuredPassword = environment.getProperty("evalora.e2e.bootstrap.password");
        String password = configuredPassword == null || configuredPassword.isBlank()
                ? null
                : configuredPassword;
        String employeeCode = trimToNull(environment.getProperty("evalora.e2e.bootstrap.employee-code"));
        String fullName = trimToNull(environment.getProperty("evalora.e2e.bootstrap.full-name"));
        if (email == null || password == null || employeeCode == null || fullName == null) {
            throw new IllegalStateException("E2E HR bootstrap requires email, password, employee code and full name.");
        }
        if (!PasswordPolicy.isValid(password)) {
            throw new IllegalStateException("E2E HR bootstrap password does not satisfy the password policy. "
                    + PasswordPolicy.MESSAGE);
        }

        String normalizedEmail = EmailNormalizer.normalize(email);
        if (userRepository.existsByRole(Role.HR)) {
            User existing = userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);
            Employee profile = existing == null
                    ? null
                    : employeeRepository.findByUser_Id(existing.getId()).orElse(null);
            if (existing != null && existing.getRole() == Role.HR
                    && profile != null
                    && profile.getEmployeeCode().equalsIgnoreCase(employeeCode)) {
                return;
            }
            throw new IllegalStateException("An unexpected HR account already exists; E2E bootstrap stopped.");
        }
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new IllegalStateException("The configured E2E HR email is already used by another account.");
        }
        if (employeeRepository.existsByEmployeeCodeIgnoreCase(employeeCode)) {
            throw new IllegalStateException("The configured E2E HR employee code is already in use.");
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
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
