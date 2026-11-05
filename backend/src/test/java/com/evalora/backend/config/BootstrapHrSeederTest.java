package com.evalora.backend.config;

import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.entity.enums.StaffStatus;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.util.PasswordPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the dev HR bootstrap decisions. The database is mocked; the
 * Spring-context behaviour is covered by the two integration tests.
 */
class BootstrapHrSeederTest {

    private static final String EMAIL = "hr@evalora.local";
    private static final String PASSWORD = "Hr@Evalora2026";

    private UserRepository userRepository;
    private EmployeeRepository employeeRepository;
    private PasswordEncoder passwordEncoder;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        employeeRepository = mock(EmployeeRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        environment = new MockEnvironment();
    }

    @Test
    void doesNothingWhenNeitherCredentialIsConfigured() {
        seeder().run(null);

        verifyNoInteractions(userRepository, employeeRepository, passwordEncoder);
    }

    @Test
    void failsWhenOnlyTheEmailIsConfigured() {
        environment.setProperty(BootstrapHrSeeder.EMAIL_PROPERTY, EMAIL);

        assertThatThrownBy(() -> seeder().run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EVALORA_BOOTSTRAP_HR_PASSWORD");
        verifyNoInteractions(userRepository, employeeRepository, passwordEncoder);
    }

    @Test
    void failsWhenOnlyThePasswordIsConfigured() {
        environment.setProperty(BootstrapHrSeeder.PASSWORD_PROPERTY, PASSWORD);

        assertThatThrownBy(() -> seeder().run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EVALORA_BOOTSTRAP_HR_EMAIL");
        verifyNoInteractions(userRepository, employeeRepository, passwordEncoder);
    }

    @Test
    void failsWhenThePasswordBreaksThePolicy() {
        environment.setProperty(BootstrapHrSeeder.EMAIL_PROPERTY, EMAIL);
        environment.setProperty(BootstrapHrSeeder.PASSWORD_PROPERTY, "weakpass");

        assertThatThrownBy(() -> seeder().run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PasswordPolicy.MESSAGE);
        verifyNoInteractions(userRepository, employeeRepository, passwordEncoder);
    }

    @Test
    void skipsWhenAnHrAccountAlreadyExists() {
        environment.setProperty(BootstrapHrSeeder.EMAIL_PROPERTY, EMAIL);
        environment.setProperty(BootstrapHrSeeder.PASSWORD_PROPERTY, PASSWORD);
        when(userRepository.existsByRole(Role.HR)).thenReturn(true);

        seeder().run(null);

        verify(userRepository, never()).saveAndFlush(any());
        verify(employeeRepository, never()).saveAndFlush(any());
    }

    @Test
    void failsWhenTheConfiguredEmailBelongsToANonHrAccount() {
        environment.setProperty(BootstrapHrSeeder.EMAIL_PROPERTY, EMAIL);
        environment.setProperty(BootstrapHrSeeder.PASSWORD_PROPERTY, PASSWORD);
        when(userRepository.existsByRole(Role.HR)).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> seeder().run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-HR");
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void createsUserAndProfileWithANormalisedEmailAndABcryptHash() {
        environment.setProperty(BootstrapHrSeeder.EMAIL_PROPERTY, "  HR@Evalora.Local  ");
        environment.setProperty(BootstrapHrSeeder.PASSWORD_PROPERTY, PASSWORD);
        environment.setProperty(BootstrapHrSeeder.CODE_PROPERTY, "HR-BOOT-1");
        environment.setProperty(BootstrapHrSeeder.FULL_NAME_PROPERTY, "Head of People");

        when(userRepository.existsByRole(Role.HR)).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(false);
        when(employeeRepository.existsByEmployeeCodeIgnoreCase("HR-BOOT-1")).thenReturn(false);
        when(passwordEncoder.encode(PASSWORD)).thenReturn("bcrypt-hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(employeeRepository.saveAndFlush(any(Employee.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        seeder().run(null);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        User user = userCaptor.getValue();
        assertThat(user.getEmail()).isEqualTo(EMAIL);
        assertThat(user.getRole()).isEqualTo(Role.HR);
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getPasswordHash()).isEqualTo("bcrypt-hash");
        assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD);

        ArgumentCaptor<Employee> employeeCaptor = ArgumentCaptor.forClass(Employee.class);
        verify(employeeRepository).saveAndFlush(employeeCaptor.capture());
        Employee employee = employeeCaptor.getValue();
        assertThat(employee.getUser()).isSameAs(user);
        assertThat(employee.getEmployeeCode()).isEqualTo("HR-BOOT-1");
        assertThat(employee.getFullName()).isEqualTo("Head of People");
        assertThat(employee.getStatus()).isEqualTo(StaffStatus.ACTIVE);
    }

    @Test
    void normalisesTheEmailBeforeTheUniquenessCheck() {
        environment.setProperty(BootstrapHrSeeder.EMAIL_PROPERTY, "HR@Evalora.Local");
        environment.setProperty(BootstrapHrSeeder.PASSWORD_PROPERTY, PASSWORD);
        when(userRepository.existsByRole(Role.HR)).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> seeder().run(null)).isInstanceOf(IllegalStateException.class);
        verify(userRepository).existsByEmailIgnoreCase(EMAIL);
        verify(userRepository, never()).saveAndFlush(any());
    }

    private BootstrapHrSeeder seeder() {
        return new BootstrapHrSeeder(userRepository, employeeRepository, passwordEncoder, environment);
    }
}
