package com.evalora.backend.controller;

import com.evalora.backend.dto.AssignManagerRequest;
import com.evalora.backend.dto.ActivateAccountRequest;
import com.evalora.backend.dto.CreateStaffRequest;
import com.evalora.backend.dto.StaffResponse;
import com.evalora.backend.dto.UpdateStaffRequest;
import com.evalora.backend.entity.Employee;
import com.evalora.backend.entity.EmployeeManagerAssignment;
import com.evalora.backend.entity.ActivationTokenEntity;
import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.entity.enums.StaffStatus;
import com.evalora.backend.repository.ActivationTokenRepository;
import com.evalora.backend.repository.EmployeeManagerAssignmentRepository;
import com.evalora.backend.repository.EmployeeRepository;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.security.JwtService;
import com.evalora.backend.service.EmailDeliveryException;
import com.evalora.backend.service.EmailService;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HR staff management through the real filter chain, controller,
 * service and an H2 database in PostgreSQL mode.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_hr;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "EVALORA_EMAIL_ENABLED=true"
})
class HrEmployeeManagementIntegrationTest {

    private static final String HR_EMAIL = "hr@evalora.local";
    private static final String HR_PASSWORD = "Hr@Evalora2026";
    private static final String EMPLOYEE_PASSWORD = "Employee@2026";
    private static final String MANAGER_PASSWORD = "Manager@2026";

    /** Password chosen by the employee/manager on the activation page. */
    private static final String ACTIVATION_PASSWORD = "Activated@2026!";

    private static final String POLICY_MESSAGE =
            "Password must be at least 8 characters long and contain a letter, a number and a special character.";

    private static final String HR_HASH = new BCryptPasswordEncoder(12).encode(HR_PASSWORD);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeeManagerAssignmentRepository assignmentRepository;

    @Autowired
    private ActivationTokenRepository activationTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoSpyBean
    private EmailService emailService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private User hrUser;
    private Employee hrProfile;
    private String hrToken;

    @BeforeEach
    void setUp() {
        assignmentRepository.deleteAll();
        employeeRepository.deleteAll();
        activationTokenRepository.deleteAll();
        userRepository.deleteAll();
        doNothing().when(emailService).sendActivationEmail(
                anyString(), any(), anyLong(), anyString(), eq("activation"));
        clearInvocations(emailService);

        hrUser = userRepository.saveAndFlush(user(HR_EMAIL, Role.HR, HR_HASH));
        hrProfile = employeeRepository.saveAndFlush(profile(hrUser, "HR-000", "HR Administrator"));
        hrToken = jwtService.generateToken(hrUser);
    }

    // ------------------------------------------------------------------ create

    @Test
    void hrCanCreateAnEmployeeWithANormalisedEmailAndABcryptPassword() throws Exception {
        StaffResponse created = createEmployee("EMP-001", "  Asha.Rao@Evalora.Local ");

        assertThat(created.role()).isEqualTo("EMPLOYEE");
        assertThat(created.email()).isEqualTo("asha.rao@evalora.local");
        assertThat(created.fullName()).isEqualTo("Asha Rao");
        assertThat(created.employeeCode()).isEqualTo("EMP-001");
        assertThat(created.department()).isEqualTo("Engineering");
        assertThat(created.designation()).isEqualTo("Developer");
        assertThat(created.joiningDate()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(created.status()).isEqualTo("ACTIVE");
        assertThat(created.accountEnabled()).isFalse();
        assertThat(created.managerId()).isNotNull();
        assertThat(created.managerName()).isEqualTo("Mona Boss");

        User user = userRepository.findByEmailIgnoreCase("asha.rao@evalora.local").orElseThrow();
        assertThat(user.getRole()).isEqualTo(Role.EMPLOYEE);
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getPasswordHash()).isNotEqualTo(ACTIVATION_PASSWORD);
        assertThat(passwordEncoder.matches(ACTIVATION_PASSWORD, user.getPasswordHash())).isTrue();

        Employee employee = employeeRepository.findByUser_Id(user.getId()).orElseThrow();
        assertThat(employee.getFullName()).isEqualTo("Asha Rao");
        assertThat(employee.getStatus()).isEqualTo(StaffStatus.ACTIVE);
        assertThat(employee.getManager().getId()).isEqualTo(created.managerId());
        assertThat(assignmentRepository.findByEmployee_IdAndEffectiveToIsNull(employee.getId()))
                .hasSize(1);
    }

    @Test
    void hrCanCreateAManagerWithItsOwnProfileAndNoManager() throws Exception {
        StaffResponse created = createManager("MGR-001", "Manager.One@Evalora.Local");

        assertThat(created.role()).isEqualTo("MANAGER");
        assertThat(created.email()).isEqualTo("manager.one@evalora.local");
        assertThat(created.accountEnabled()).isFalse();
        assertThat(created.managerId()).isNull();

        User user = userRepository.findByEmailIgnoreCase("manager.one@evalora.local").orElseThrow();
        assertThat(user.getRole()).isEqualTo(Role.MANAGER);
        assertThat(user.isEnabled()).isTrue();
        assertThat(passwordEncoder.matches(ACTIVATION_PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(assignmentRepository.count()).isZero();
    }

    @Test
    void activationAndResendUseTheRegisteredEmployeeEmail() throws Exception {
        StaffResponse manager = createManager("MGR-RECIPIENT", "recipient.manager@evalora.local");
        clearInvocations(emailService);

        MvcResult result = mockMvc.perform(authed(post("/api/hr/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest("Pending", "Employee",
                                "Pending.Employee@Evalora.Local", "EMP-RECIPIENT",
                                manager.id())))))
                .andExpect(status().isCreated())
                .andReturn();
        StaffResponse created = read(result);

        verify(emailService).sendActivationEmail(
                eq(created.email()), eq(created.fullName()), eq(created.userId()), anyString(), eq("activation"));

        clearInvocations(emailService);
        mockMvc.perform(authed(patch(
                        "/api/auth/hr/employees/{employeeId}/resend-activation", created.id())))
                .andExpect(status().isOk());

        verify(emailService).sendActivationEmail(
                eq(created.email()), eq(created.fullName()), eq(created.userId()), anyString(), eq("activation"));
    }

    @Test
    void activationDeliveryFailureDoesNotRollBackEmployeeCreation() throws Exception {
        StaffResponse manager = createManager("MGR-DELIVERY", "delivery.manager@evalora.local");

        doThrow(new EmailDeliveryException("simulated provider failure"))
                .when(emailService).sendActivationEmail(
                        anyString(), any(), anyLong(), anyString(), eq("activation"));

        MvcResult result = mockMvc.perform(authed(post("/api/hr/employees"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest("Pending", "Employee",
                                "pending.delivery@evalora.local", "EMP-DELIVERY",
                                manager.id()))))
                .andExpect(status().isCreated())
                .andReturn();
        StaffResponse created = read(result);

        assertThat(userRepository.findByEmailIgnoreCase("pending.delivery@evalora.local"))
                .isPresent();
        assertThat(employeeRepository.findByUser_Id(created.userId())).isPresent();
        assertThat(activationTokenRepository.findByUser_Id(created.userId())).hasSize(1);
    }

    @Test
    void resendActivationDeliveryFailureIsReportedHonestlyAndCanBeRetried() throws Exception {
        StaffResponse manager = createManager("MGR-RETRY", "retry.manager@evalora.local");

        MvcResult createdResult = mockMvc.perform(authed(post("/api/hr/employees"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest("Pending", "Employee",
                                "pending.retry@evalora.local", "EMP-RETRY",
                                manager.id()))))
                .andExpect(status().isCreated())
                .andReturn();
        StaffResponse pending = read(createdResult);
        clearInvocations(emailService);

        doThrow(new EmailDeliveryException("simulated provider failure"))
                .when(emailService).sendActivationEmail(
                        anyString(), any(), anyLong(), anyString(), eq("activation"));

        mockMvc.perform(authed(patch(
                        "/api/auth/hr/employees/{employeeId}/resend-activation", pending.id())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Activation email could not be delivered. The account is still pending; "
                                + "try again after checking email delivery."));

        // The token rotation committed before the provider call, so the account
        // remains pending and the resend can be retried once delivery works.
        assertThat(userRepository.findById(pending.userId()).orElseThrow().isEnabled()).isFalse();
        assertThat(activationTokenRepository.findByUser_Id(pending.userId())).hasSize(1);

        doNothing().when(emailService).sendActivationEmail(
                anyString(), any(), anyLong(), anyString(), eq("activation"));
        clearInvocations(emailService);
        mockMvc.perform(authed(patch(
                        "/api/auth/hr/employees/{employeeId}/resend-activation", pending.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Activation email sent."));
        verify(emailService).sendActivationEmail(
                eq(pending.email()), eq(pending.fullName()), eq(pending.userId()), anyString(), eq("activation"));
    }

    // ------------------------------------------------------------------ activation

    @Test
    void activationSetsTheUsersOwnPasswordAndTheLinkIsSingleUse() throws Exception {
        StaffResponse employee = createPendingEmployee("EMP-ACT-1", "activate.one@evalora.local");
        String token = activationTokenFor(employee);

        mockMvc.perform(post("/api/auth/activate-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ActivateAccountRequest(
                                token, ACTIVATION_PASSWORD, ACTIVATION_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Account activated successfully."))
                .andExpect(jsonPath("$.enabled").value(true));

        User user = userRepository.findById(employee.userId()).orElseThrow();
        assertThat(user.isEnabled()).isTrue();
        assertThat(passwordEncoder.matches(ACTIVATION_PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(EMPLOYEE_PASSWORD, user.getPasswordHash())).isFalse();
        assertThat(activationTokenRepository.findByUser_Id(employee.userId())).isEmpty();

        // Single use: replaying the same link cannot reactivate or reset anything.
        mockMvc.perform(post("/api/auth/activate-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ActivateAccountRequest(
                                token, ACTIVATION_PASSWORD, ACTIVATION_PASSWORD))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "This activation link is invalid or has already been used. "
                                + "Ask your HR administrator to resend the activation email."));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", employee.email(),
                                "password", ACTIVATION_PASSWORD))))
                .andExpect(status().isOk());
    }

    @Test
    void loginIsRejectedBeforeActivationAndSucceedsAfterActivation() throws Exception {
        StaffResponse employee = createPendingEmployee("EMP-LOGIN", "before.activation@evalora.local");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", employee.email(),
                                "password", ACTIVATION_PASSWORD))))
                .andExpect(status().isUnauthorized());

        activateAccount(employee);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", employee.email(),
                                "password", ACTIVATION_PASSWORD))))
                .andExpect(status().isOk());
    }

    @Test
    void activationRejectsAnUnknownToken() throws Exception {
        mockMvc.perform(post("/api/auth/activate-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ActivateAccountRequest(
                                "0".repeat(64), ACTIVATION_PASSWORD, ACTIVATION_PASSWORD))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "This activation link is invalid or has already been used. "
                                + "Ask your HR administrator to resend the activation email."));
    }

    @Test
    void activationRejectsAnExpiredToken() throws Exception {
        StaffResponse employee = createPendingEmployee("EMP-ACT-3", "activate.expired@evalora.local");
        String token = activationTokenFor(employee);

        ActivationTokenEntity stored = activationTokenRepository.findByUser_Id(employee.userId())
                .stream().findFirst().orElseThrow();
        stored.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        activationTokenRepository.saveAndFlush(stored);

        mockMvc.perform(post("/api/auth/activate-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ActivateAccountRequest(
                                token, ACTIVATION_PASSWORD, ACTIVATION_PASSWORD))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "This activation link has expired. "
                                + "Ask your HR administrator to resend the activation email."));

        assertThat(userRepository.findById(employee.userId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void activationEnforcesPasswordConfirmationAndPolicyBeforeConsumingTheToken() throws Exception {
        StaffResponse employee = createPendingEmployee("EMP-ACT-4", "activate.policy@evalora.local");
        String token = activationTokenFor(employee);

        mockMvc.perform(post("/api/auth/activate-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ActivateAccountRequest(
                                token, ACTIVATION_PASSWORD, "Different@2026!"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "New password and confirmation do not match."));

        mockMvc.perform(post("/api/auth/activate-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ActivateAccountRequest(token, "weakpassword", "weakpassword"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(POLICY_MESSAGE));

        // A rejected password never consumes the link.
        assertThat(activationTokenRepository.findByUser_Id(employee.userId())).hasSize(1);
        mockMvc.perform(post("/api/auth/activate-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ActivateAccountRequest(
                                token, ACTIVATION_PASSWORD, ACTIVATION_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void activationReportsAnAlreadyActiveAccountWithoutChangingIt() throws Exception {
        StaffResponse employee = createPendingEmployee("EMP-ACT-5", "activate.active@evalora.local");
        String token = activationTokenFor(employee);

        User user = userRepository.findById(employee.userId()).orElseThrow();
        user.setEnabled(true);
        userRepository.saveAndFlush(user);

        mockMvc.perform(post("/api/auth/activate-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ActivateAccountRequest(
                                token, ACTIVATION_PASSWORD, ACTIVATION_PASSWORD))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "This account is already active. Sign in with your password."));
    }

    @Test
    void hrCreatedEmployeeAppearsOnlyInSelectedManagersDirectReports() throws Exception {
        StaffResponse manager = createManager("MGR-010", "mona@evalora.local");
        createManager("MGR-011", "other.manager@evalora.local");
        StaffResponse employee = createEmployee("EMP-010", "asha@evalora.local", manager.id());
        User selectedManager = userRepository.findByEmailIgnoreCase("mona@evalora.local").orElseThrow();
        User otherManager = userRepository.findByEmailIgnoreCase("other.manager@evalora.local")
                .orElseThrow();

        mockMvc.perform(authed(get("/api/manager/direct-reports"), jwtService.generateToken(selectedManager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].employeeCode").value("EMP-010"))
                .andExpect(jsonPath("$[0].fullName").value("Asha Rao"));

        mockMvc.perform(authed(get("/api/manager/direct-reports"), jwtService.generateToken(otherManager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void creatingAManagerWithAManagerIdIsRejected() throws Exception {
        mockMvc.perform(authed(post("/api/hr/managers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest("Mona", "Boss", "mona@evalora.local",
                                "MGR-002", hrProfile.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Managers cannot be assigned a manager in v1."));

        assertThat(userRepository.findByEmailIgnoreCase("mona@evalora.local")).isEmpty();
    }

    @Test
    void employeeCreationRequiresManagerWhetherManagerIdIsMissingOrNull() throws Exception {
        mockMvc.perform(authed(post("/api/hr/employees"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "firstName", "Asha", "lastName", "Rao", "email", "asha@evalora.local",
                                "employeeCode", "EMP-001", "department", "Engineering",
                                "designation", "Developer", "joiningDate", "2026-01-05"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("A reporting manager is required for employees."));

        mockMvc.perform(authed(post("/api/hr/employees"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest("Asha", "Rao", "asha@evalora.local",
                                "EMP-001", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("A reporting manager is required for employees."));

        assertThat(userRepository.findByEmailIgnoreCase("asha@evalora.local")).isEmpty();
        assertThat(employeeRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectedManagerRequirementDoesNotChangeAnExistingUnassignedEmployee() throws Exception {
        User legacyUser = user("legacy@evalora.local", Role.EMPLOYEE,
                new BCryptPasswordEncoder(12).encode(EMPLOYEE_PASSWORD));
        legacyUser = userRepository.saveAndFlush(legacyUser);
        Employee legacy = employeeRepository.saveAndFlush(profile(legacyUser, "EMP-LEGACY", "Legacy Employee"));

        mockMvc.perform(authed(post("/api/hr/employees"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest("Asha", "Rao", "asha@evalora.local",
                                "EMP-001", null))))
                .andExpect(status().isBadRequest());

        Employee preserved = employeeRepository.findById(legacy.getId()).orElseThrow();
        assertThat(preserved.getEmployeeCode()).isEqualTo("EMP-LEGACY");
        assertThat(preserved.getFullName()).isEqualTo("Legacy Employee");
        assertThat(preserved.getManager()).isNull();
        assertThat(userRepository.findById(legacyUser.getId())).isPresent();
    }

    @Test
    void duplicateEmailIsRejectedRegardlessOfCase() throws Exception {
        createEmployee("EMP-001", "asha@evalora.local");

        mockMvc.perform(authed(post("/api/hr/employees"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest("Another", "Asha", "ASHA@EVALORA.LOCAL",
                                "EMP-002", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An account with this email already exists."));
    }

    @Test
    void duplicateEmployeeCodeIsRejected() throws Exception {
        createEmployee("EMP-001", "asha@evalora.local");

        mockMvc.perform(authed(post("/api/hr/employees"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest("Another", "Person", "other@evalora.local",
                                "emp-001", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An employee with this code already exists."));
    }

    @Test
    void employeeCreationDoesNotRequireOrAcceptAnInitialPassword() throws Exception {
        StaffResponse employee = createPendingEmployee("EMP-NOPASS", "no.password@evalora.local");

        assertThat(employee.accountEnabled()).isFalse();
        User user = userRepository.findById(employee.userId()).orElseThrow();
        assertThat(user.isEnabled()).isFalse();
        assertThat(user.getPasswordHash()).startsWith("$2");
        assertThat(activationTokenRepository.findByUser_Id(employee.userId())).hasSize(1);
        verify(emailService).sendActivationEmail(
                eq(employee.email()), eq(employee.fullName()), eq(employee.userId()),
                anyString(), eq("activation"));
    }

    @Test
    void invalidEmailAndMissingFieldsAreRejected() throws Exception {
        mockMvc.perform(authed(post("/api/hr/employees"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest("Asha", "Rao", "not-an-email",
                                "EMP-001", null))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(authed(post("/api/hr/employees"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "asha@evalora.local"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anEmployeeCanBeCreatedAlreadyAssignedToAManager() throws Exception {
        StaffResponse manager = createManager("MGR-001", "manager@evalora.local");
        StaffResponse employee = createEmployee("EMP-001", "asha@evalora.local", manager.id());

        assertThat(employee.managerId()).isEqualTo(manager.id());
        assertThat(employee.managerName()).isEqualTo("Mona Boss");

        List<EmployeeManagerAssignment> history =
                assignmentRepository.findByEmployee_IdOrderByEffectiveFromAsc(employee.id());
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().getManager().getId()).isEqualTo(manager.id());
        assertThat(history.getFirst().getEffectiveTo()).isNull();
        assertThat(history.getFirst().getAssignedBy().getId()).isEqualTo(hrUser.getId());
    }

    @Test
    void assignmentToAnUnknownManagerIsRejected() throws Exception {
        mockMvc.perform(authed(post("/api/hr/employees"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest("Asha", "Rao", "asha@evalora.local",
                                "EMP-001", 999_999L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Manager not found."));

        assertThat(userRepository.findByEmailIgnoreCase("asha@evalora.local")).isEmpty();
    }

    @Test
    void anHrAccountCannotBeSelectedAsReportingManagerDuringCreation() throws Exception {
        mockMvc.perform(authed(post("/api/hr/employees"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest("Asha", "Rao", "asha@evalora.local",
                                "EMP-001", hrProfile.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The selected account is not a manager."));

        assertThat(userRepository.findByEmailIgnoreCase("asha@evalora.local")).isEmpty();
    }

    @Test
    void aNonManagerCannotBeUsedAsAManager() throws Exception {
        StaffResponse otherEmployee = createEmployee("EMP-002", "other@evalora.local");

        mockMvc.perform(authed(post("/api/hr/employees"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest("Asha", "Rao", "asha@evalora.local",
                                "EMP-001", otherEmployee.id()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The selected account is not a manager."));
    }

    @Test
    void anInactiveManagerCannotReceiveNewEmployees() throws Exception {
        StaffResponse manager = createManager("MGR-001", "manager@evalora.local");
        mockMvc.perform(authed(patch("/api/hr/managers/{id}/deactivate", manager.id())))
                .andExpect(status().isOk());

        mockMvc.perform(authed(post("/api/hr/employees"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest("Asha", "Rao", "asha@evalora.local",
                                "EMP-001", manager.id()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The selected manager is inactive."));
    }

    // -------------------------------------------------------------------- read

    @Test
    void listingEmployeesReturnsOnlyActiveEmployeesAndExcludesManagers() throws Exception {
        StaffResponse active = createEmployee("EMP-001", "asha@evalora.local");
        StaffResponse inactive = createEmployee("EMP-002", "bala@evalora.local");
        createManager("MGR-001", "manager@evalora.local");

        mockMvc.perform(authed(patch("/api/hr/employees/{id}/deactivate", inactive.id())))
                .andExpect(status().isOk());

        mockMvc.perform(authed(get("/api/hr/employees")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(active.id()))
                .andExpect(jsonPath("$[0].role").value("EMPLOYEE"));

        mockMvc.perform(authed(get("/api/hr/employees").param("status", "INACTIVE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(inactive.id()));
    }

    @Test
    void listingManagersReturnsOnlyManagers() throws Exception {
        StaffResponse manager = createManager("MGR-001", "manager@evalora.local");
        createEmployee("EMP-001", "asha@evalora.local", manager.id());

        mockMvc.perform(authed(get("/api/hr/managers")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(manager.id()))
                .andExpect(jsonPath("$[0].role").value("MANAGER"));
    }

    @Test
    void employeeAndManagerDetailsAreAvailable() throws Exception {
        StaffResponse manager = createManager("MGR-001", "manager@evalora.local");
        StaffResponse employee = createEmployee("EMP-001", "asha@evalora.local", manager.id());

        mockMvc.perform(authed(get("/api/hr/employees/{id}", employee.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeCode").value("EMP-001"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());

        mockMvc.perform(authed(get("/api/hr/managers/{id}", manager.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MANAGER"));
    }

    @Test
    void aProfileOfTheWrongRoleIsNotReturned() throws Exception {
        StaffResponse employee = createEmployee("EMP-001", "asha@evalora.local");
        StaffResponse manager = createManager("MGR-001", "manager@evalora.local");

        mockMvc.perform(authed(get("/api/hr/managers/{id}", employee.id())))
                .andExpect(status().isNotFound());
        mockMvc.perform(authed(get("/api/hr/employees/{id}", manager.id())))
                .andExpect(status().isNotFound());
        mockMvc.perform(authed(get("/api/hr/employees/{id}", 999_999L)))
                .andExpect(status().isNotFound());
        mockMvc.perform(authed(patch("/api/hr/managers/{id}/deactivate", employee.id())))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ update

    @Test
    void hrCanUpdateAnEmployeeProfileWithoutChangingRoleOrAccountState() throws Exception {
        StaffResponse employee = createEmployee("EMP-001", "asha@evalora.local");

        mockMvc.perform(authed(put("/api/hr/employees/{id}", employee.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateStaffRequest("Asha", "Rao-Sharma", " ASHA.NEW@Evalora.Local ",
                                "EMP-100", "Product", "Senior Developer", LocalDate.of(2025, 6, 1)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("asha.new@evalora.local"))
                .andExpect(jsonPath("$.employeeCode").value("EMP-100"))
                .andExpect(jsonPath("$.fullName").value("Asha Rao-Sharma"))
                .andExpect(jsonPath("$.department").value("Product"))
                .andExpect(jsonPath("$.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.accountEnabled").value(true));

        User user = userRepository.findByEmailIgnoreCase("asha.new@evalora.local").orElseThrow();
        assertThat(user.getEmail()).isEqualTo("asha.new@evalora.local");
        assertThat(user.getRole()).isEqualTo(Role.EMPLOYEE);
        assertThat(passwordEncoder.matches(ACTIVATION_PASSWORD, user.getPasswordHash())).isTrue();
    }

    @Test
    void hrCanUpdateAManagerProfile() throws Exception {
        StaffResponse manager = createManager("MGR-001", "manager@evalora.local");

        mockMvc.perform(authed(put("/api/hr/managers/{id}", manager.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateStaffRequest("Mona", "Boss", "manager@evalora.local",
                                "MGR-001", "Operations", "Head of Ops", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.department").value("Operations"))
                .andExpect(jsonPath("$.designation").value("Head of Ops"))
                .andExpect(jsonPath("$.joiningDate").doesNotExist());
    }

    @Test
    void profileUpdateCannotStealAnotherAccountsEmail() throws Exception {
        createEmployee("EMP-001", "asha@evalora.local");
        StaffResponse other = createEmployee("EMP-002", "bala@evalora.local");

        mockMvc.perform(authed(put("/api/hr/employees/{id}", other.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateStaffRequest("Bala", "K", "ASHA@Evalora.Local",
                                "EMP-002", null, null, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An account with this email already exists."));
    }

    @Test
    void invalidProfileUpdateIsRejected() throws Exception {
        StaffResponse employee = createEmployee("EMP-001", "asha@evalora.local");

        mockMvc.perform(authed(put("/api/hr/employees/{id}", employee.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateStaffRequest(" ", "Rao", "asha@evalora.local",
                                "EMP-001", null, null, null))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(authed(put("/api/hr/employees/{id}", employee.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateStaffRequest("Asha", "Rao", "bad-email",
                                "EMP-001", null, null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void securityFieldsInAnUpdateBodyAreIgnored() throws Exception {
        StaffResponse employee = createEmployee("EMP-001", "asha@evalora.local");
        String hashBefore = userRepository.findByEmailIgnoreCase("asha@evalora.local")
                .orElseThrow().getPasswordHash();

        String body = json(Map.of(
                "firstName", "Asha",
                "lastName", "Rao",
                "email", "asha@evalora.local",
                "employeeCode", "EMP-001",
                "role", "HR",
                "status", "INACTIVE",
                "passwordHash", "$2a$12$attacker",
                "enabled", false));

        mockMvc.perform(authed(put("/api/hr/employees/{id}", employee.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.accountEnabled").value(true));

        User user = userRepository.findByEmailIgnoreCase("asha@evalora.local").orElseThrow();
        assertThat(user.getRole()).isEqualTo(Role.EMPLOYEE);
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getPasswordHash()).isEqualTo(hashBefore);
    }

    // -------------------------------------------------------------- assignment

    @Test
    void hrCanAssignAndReassignAnEmployeePreservingHistory() throws Exception {
        StaffResponse managerOne = createManager("MGR-001", "manager1@evalora.local");
        StaffResponse managerTwo = createManager("MGR-002", "manager2@evalora.local");
        StaffResponse employee = createEmployee("EMP-001", "asha@evalora.local", managerOne.id());

        mockMvc.perform(authed(put("/api/hr/employees/{id}/manager", employee.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AssignManagerRequest(managerTwo.id()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerId").value(managerTwo.id()));

        List<EmployeeManagerAssignment> history =
                assignmentRepository.findByEmployee_IdOrderByEffectiveFromAsc(employee.id());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getManager().getId()).isEqualTo(managerOne.id());
        assertThat(history.get(0).getEffectiveTo()).isNotNull();
        assertThat(history.get(1).getManager().getId()).isEqualTo(managerTwo.id());
        assertThat(history.get(1).getEffectiveTo()).isNull();
        assertThat(assignmentRepository.countByEmployee_IdAndEffectiveToIsNull(employee.id())).isEqualTo(1);

        Employee reloaded = employeeRepository.findById(employee.id()).orElseThrow();
        assertThat(reloaded.getManager().getId()).isEqualTo(managerTwo.id());
    }

    @Test
    void assignmentValidationCoversSelfRoleAndState() throws Exception {
        StaffResponse employee = createEmployee("EMP-001", "asha@evalora.local");
        StaffResponse otherEmployee = createEmployee("EMP-002", "bala@evalora.local");

        mockMvc.perform(authed(put("/api/hr/employees/{id}/manager", employee.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AssignManagerRequest(employee.id()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("An employee cannot be their own manager."));

        mockMvc.perform(authed(put("/api/hr/employees/{id}/manager", employee.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AssignManagerRequest(otherEmployee.id()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The selected account is not a manager."));

        mockMvc.perform(authed(put("/api/hr/employees/{id}/manager", employee.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AssignManagerRequest(hrProfile.getId()))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(authed(put("/api/hr/employees/{id}/manager", employee.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AssignManagerRequest(999_999L))))
                .andExpect(status().isNotFound());
    }

    @Test
    void anInactiveEmployeeCannotBeAssignedAManager() throws Exception {
        StaffResponse manager = createManager("MGR-001", "manager@evalora.local");
        StaffResponse employee = createEmployee("EMP-001", "asha@evalora.local");

        mockMvc.perform(authed(patch("/api/hr/employees/{id}/deactivate", employee.id())))
                .andExpect(status().isOk());

        mockMvc.perform(authed(put("/api/hr/employees/{id}/manager", employee.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AssignManagerRequest(manager.id()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only an active employee can be assigned a manager."));
    }

    // ------------------------------------------------------- activate/deactivate

    @Test
    void employeeDeactivationDisablesLoginClosesTheAssignmentAndPreservesHistory() throws Exception {
        StaffResponse manager = createManager("MGR-001", "manager@evalora.local");
        StaffResponse employee = createEmployee("EMP-001", "asha@evalora.local", manager.id());

        mockMvc.perform(authed(patch("/api/hr/employees/{id}/deactivate", employee.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.accountEnabled").value(false))
                .andExpect(jsonPath("$.managerId").doesNotExist());

        User user = userRepository.findByEmailIgnoreCase("asha@evalora.local").orElseThrow();
        assertThat(user.isEnabled()).isFalse();

        Employee profile = employeeRepository.findById(employee.id()).orElseThrow();
        assertThat(profile.getStatus()).isEqualTo(StaffStatus.INACTIVE);
        assertThat(profile.getManager()).isNull();

        List<EmployeeManagerAssignment> history =
                assignmentRepository.findByEmployee_IdOrderByEffectiveFromAsc(employee.id());
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().getEffectiveTo()).isNotNull();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "asha@evalora.local", "password", ACTIVATION_PASSWORD))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(authed(get("/api/hr/employees")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void employeeReactivationEnablesLoginButDoesNotRestoreTheOldManager() throws Exception {
        StaffResponse manager = createManager("MGR-001", "manager@evalora.local");
        StaffResponse employee = createEmployee("EMP-001", "asha@evalora.local", manager.id());

        mockMvc.perform(authed(patch("/api/hr/employees/{id}/deactivate", employee.id())))
                .andExpect(status().isOk());
        mockMvc.perform(authed(patch("/api/hr/employees/{id}/reactivate", employee.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.accountEnabled").value(true))
                .andExpect(jsonPath("$.managerId").doesNotExist());

        assertThat(assignmentRepository.countByEmployee_IdAndEffectiveToIsNull(employee.id())).isZero();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "asha@evalora.local", "password", ACTIVATION_PASSWORD))))
                .andExpect(status().isOk());
    }

    @Test
    void repeatedStateChangesAreRejected() throws Exception {
        StaffResponse employee = createEmployee("EMP-001", "asha@evalora.local");

        mockMvc.perform(authed(patch("/api/hr/employees/{id}/reactivate", employee.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Employee is already active."));

        mockMvc.perform(authed(patch("/api/hr/employees/{id}/deactivate", employee.id())))
                .andExpect(status().isOk());
        mockMvc.perform(authed(patch("/api/hr/employees/{id}/deactivate", employee.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Employee is already inactive."));
    }

    @Test
    void managerDeactivationIsRejectedWhileActiveEmployeesAreAssigned() throws Exception {
        StaffResponse manager = createManager("MGR-001", "manager@evalora.local");
        createEmployee("EMP-001", "asha@evalora.local", manager.id());

        mockMvc.perform(authed(patch("/api/hr/managers/{id}/deactivate", manager.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Reassign")));

        Employee reloaded = employeeRepository.findById(manager.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(StaffStatus.ACTIVE);
        assertThat(userRepository.findByEmailIgnoreCase("manager@evalora.local").orElseThrow().isEnabled())
                .isTrue();
    }

    @Test
    void managerCanBeDeactivatedAfterReportsAreReassigned() throws Exception {
        StaffResponse managerOne = createManager("MGR-001", "manager1@evalora.local");
        StaffResponse managerTwo = createManager("MGR-002", "manager2@evalora.local");
        StaffResponse employee = createEmployee("EMP-001", "asha@evalora.local", managerOne.id());

        mockMvc.perform(authed(put("/api/hr/employees/{id}/manager", employee.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AssignManagerRequest(managerTwo.id()))))
                .andExpect(status().isOk());

        mockMvc.perform(authed(patch("/api/hr/managers/{id}/deactivate", managerOne.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.accountEnabled").value(false));

        Employee reloaded = employeeRepository.findById(managerOne.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(StaffStatus.INACTIVE);
        assertThat(userRepository.findByEmailIgnoreCase("manager1@evalora.local").orElseThrow().isEnabled())
                .isFalse();
        assertThat(assignmentRepository.countByEmployee_IdAndEffectiveToIsNull(employee.id())).isEqualTo(1);
        assertThat(employeeRepository.findById(employee.id()).orElseThrow()
                .getManager().getId()).isEqualTo(managerTwo.id());
    }

    @Test
    void managerReactivationDoesNotRecreateEmployeeAssignments() throws Exception {
        StaffResponse manager = createManager("MGR-001", "manager@evalora.local");
        StaffResponse employee = createEmployee("EMP-001", "asha@evalora.local", manager.id());
        mockMvc.perform(authed(patch("/api/hr/employees/{id}/deactivate", employee.id())))
                .andExpect(status().isOk());
        mockMvc.perform(authed(patch("/api/hr/managers/{id}/deactivate", manager.id())))
                .andExpect(status().isOk());

        mockMvc.perform(authed(patch("/api/hr/managers/{id}/reactivate", manager.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.accountEnabled").value(true));

        assertThat(assignmentRepository.countByEmployee_IdAndEffectiveToIsNull(employee.id())).isZero();
        assertThat(assignmentRepository.findByEmployee_IdOrderByEffectiveFromAsc(employee.id()))
                .singleElement()
                .satisfies(history -> assertThat(history.getEffectiveTo()).isNotNull());
        assertThat(employeeRepository.findById(employee.id()).orElseThrow().getManager()).isNull();
    }

    // ----------------------------------------------------------------- helpers

    private StaffResponse createEmployee(String code, String email) throws Exception {
        List<Employee> activeManagers = employeeRepository
                .findByUser_RoleAndStatusOrderByFullNameAsc(Role.MANAGER, StaffStatus.ACTIVE)
                .stream()
                .filter(manager -> manager.getUser().isEnabled())
                .toList();
        Long managerId = activeManagers.isEmpty()
                ? createManager("MGR-DEFAULT", "default.manager@evalora.local").id()
                : activeManagers.getFirst().getId();
        return createEmployee(code, email, managerId);
    }

    private StaffResponse createEmployee(String code, String email, Long managerId) throws Exception {
        clearInvocations(emailService);
        MvcResult result = mockMvc.perform(authed(post("/api/hr/employees"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest("Asha", "Rao", email, code,
                                managerId))))
                .andExpect(status().isCreated())
                .andReturn();
        StaffResponse created = read(result);
        activateAccount(created);
        return created;
    }

    private StaffResponse createManager(String code, String email) throws Exception {
        clearInvocations(emailService);
        MvcResult result = mockMvc.perform(authed(post("/api/hr/managers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest("Mona", "Boss", email, code,
                                null))))
                .andExpect(status().isCreated())
                .andReturn();
        StaffResponse created = read(result);
        activateAccount(created);
        return created;
    }

    private void activateAccount(StaffResponse created) throws Exception {
        String token = activationTokenFor(created);
        mockMvc.perform(authed(post("/api/auth/activate-account"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ActivateAccountRequest(
                                token, ACTIVATION_PASSWORD, ACTIVATION_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    private String activationTokenFor(StaffResponse created) {
        ArgumentCaptor<String> activationUrl = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendActivationEmail(
                eq(created.email()), eq(created.fullName()), eq(created.userId()),
                activationUrl.capture(), eq("activation"));

        String url = activationUrl.getValue();
        return url.substring(url.indexOf("?token=") + "?token=".length());
    }

    private StaffResponse createPendingEmployee(String code, String email) throws Exception {
        StaffResponse manager = createManager("MGR-" + code, "manager." + code.toLowerCase() + "@evalora.local");
        clearInvocations(emailService);
        MvcResult result = mockMvc.perform(authed(post("/api/hr/employees"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest("Pending", "Employee", email, code,
                                manager.id()))))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result);
    }

    private CreateStaffRequest createRequest(String first, String last, String email, String code,
                                             Long managerId) {
        return new CreateStaffRequest(first, last, email, code, "Engineering", "Developer",
                LocalDate.of(2026, 1, 5), managerId);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + hrToken);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private StaffResponse read(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), StaffResponse.class);
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private static User user(String email, Role role, String hash) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(hash);
        user.setRole(role);
        user.setEnabled(true);
        return user;
    }

    private static Employee profile(User user, String code, String fullName) {
        Employee employee = new Employee();
        employee.setUser(user);
        employee.setEmployeeCode(code);
        employee.setFullName(fullName);
        employee.setStatus(StaffStatus.ACTIVE);
        return employee;
    }
}
