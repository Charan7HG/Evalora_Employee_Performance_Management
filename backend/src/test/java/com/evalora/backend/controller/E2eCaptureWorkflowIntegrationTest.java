package com.evalora.backend.controller;

import com.evalora.backend.dto.LoginResponse;
import com.evalora.backend.dto.StaffResponse;
import com.evalora.backend.dto.VerifyOtpResponse;
import com.evalora.backend.service.CapturedEmail;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({ "e2e-capture", "e2e-bootstrap" })
class E2eCaptureWorkflowIntegrationTest {

    private static final String HR_EMAIL = "e2e.hr@evalora.local";
    private static final String HR_PASSWORD = "E2eHrPassword2026!";
    private static final String MANAGER_EMAIL = "e2e.manager@evalora.local";
    private static final String EMPLOYEE_EMAIL = "e2e.employee@evalora.local";
    private static final String ACTIVATION_PASSWORD = "E2eActivatedPassword2026!";
    private static final String RESET_PASSWORD = "E2eResetPassword2026!";
    private static final Path CAPTURE_DIRECTORY = createCaptureDirectory();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void e2eProperties(DynamicPropertyRegistry registry) {
        registry.add("evalora.e2e.capture.directory", CAPTURE_DIRECTORY::toString);
        registry.add("evalora.e2e.bootstrap.email", () -> HR_EMAIL);
        registry.add("evalora.e2e.bootstrap.password", () -> HR_PASSWORD);
        registry.add("evalora.e2e.bootstrap.employee-code", () -> "HR-E2E");
        registry.add("evalora.e2e.bootstrap.full-name", () -> "E2E HR");
        registry.add("evalora.frontend.base-url", () -> "http://localhost:15173");
        registry.add("EVALORA_EMAIL_ENABLED", () -> "false");
    }

    @Test
    void createsActivatesAndAuthenticatesStaffThenCompletesOtpPasswordRecovery() throws Exception {
        LoginResponse hr = login(HR_EMAIL, HR_PASSWORD);
        assertThat(hr.user().role()).isEqualTo("HR");

        StaffResponse manager = createStaff("/api/hr/managers", "Mona", "Manager",
                MANAGER_EMAIL, "MGR-E2E", null, hr.token());
        CapturedEmail managerMessage = findCapture("activation", MANAGER_EMAIL, manager.userId());
        String managerActivationToken = activationToken(managerMessage);
        assertActivationGetIsNotSupported(managerActivationToken, hr.token());
        activate(managerActivationToken, ACTIVATION_PASSWORD);
        assertThat(login(MANAGER_EMAIL, ACTIVATION_PASSWORD).user().role()).isEqualTo("MANAGER");

        StaffResponse employee = createStaff("/api/hr/employees", "Asha", "Employee",
                EMPLOYEE_EMAIL, "EMP-E2E", manager.id(), hr.token());
        CapturedEmail employeeMessage = findCapture("activation", EMPLOYEE_EMAIL, employee.userId());
        String employeeActivationToken = activationToken(employeeMessage);
        activate(employeeActivationToken, ACTIVATION_PASSWORD);
        assertThat(login(EMPLOYEE_EMAIL, ACTIVATION_PASSWORD).user().role()).isEqualTo("EMPLOYEE");

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", EMPLOYEE_EMAIL))))
                .andExpect(status().isOk());
        CapturedEmail otpMessage = findCapture("otp", EMPLOYEE_EMAIL, null);
        String otp = otpMessage.content();
        assertThat(otp.matches("\\d{6}")).isTrue();

        MvcResult verification = mockMvc.perform(post("/api/auth/verify-reset-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", EMPLOYEE_EMAIL, "otp", otp))))
                .andExpect(status().isOk())
                .andReturn();
        VerifyOtpResponse verified = objectMapper.readValue(
                verification.getResponse().getContentAsString(), VerifyOtpResponse.class);
        assertThat(verified.valid()).isTrue();
        assertThat(verified.ticket()).isNotBlank();

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "token", verified.ticket(),
                                "newPassword", RESET_PASSWORD,
                                "confirmPassword", RESET_PASSWORD))))
                .andExpect(status().isOk());
        assertThat(login(EMPLOYEE_EMAIL, RESET_PASSWORD).user().email()).isEqualTo(EMPLOYEE_EMAIL);

        mockMvc.perform(authed(get("/api/e2e/captures"), hr.token()))
                .andExpect(status().isNotFound());
    }

    @AfterAll
    static void removeTemporaryCaptures() throws IOException {
        if (Files.exists(CAPTURE_DIRECTORY)) {
            try (Stream<Path> paths = Files.walk(CAPTURE_DIRECTORY)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private StaffResponse createStaff(String route, String firstName, String lastName,
                                      String email, String code,
                                      Long managerId, String hrToken) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("firstName", firstName);
        request.put("lastName", lastName);
        request.put("email", email);
        request.put("employeeCode", code);
        request.put("department", "Engineering");
        request.put("designation", "Developer");
        request.put("joiningDate", LocalDate.of(2026, 1, 5).toString());
        request.put("managerId", managerId);

        MvcResult result = mockMvc.perform(authed(post(route), hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readValue(result.getResponse().getContentAsString(), StaffResponse.class);
    }

    private LoginResponse login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), LoginResponse.class);
    }

    private void activate(String token, String password) throws Exception {
        mockMvc.perform(post("/api/auth/activate-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "token", token,
                                "newPassword", password,
                                "confirmPassword", password))))
                .andExpect(status().isOk());
    }

    private void assertActivationGetIsNotSupported(String token, String hrToken) throws Exception {
        mockMvc.perform(authed(get("/api/auth/activate-account").param("token", token), hrToken))
                .andExpect(status().isMethodNotAllowed());
    }

    private CapturedEmail findCapture(String type, String recipient, Long accountId) throws IOException {
        List<CapturedEmail> captures;
        try (Stream<Path> files = Files.list(CAPTURE_DIRECTORY)) {
            captures = files.map(this::readCapture).toList();
        }
        return captures.stream()
                .filter(message -> message.type().equals(type)
                        && message.recipient().equals(recipient)
                        && java.util.Objects.equals(message.accountId(), accountId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected E2E email was not captured."));
    }

    private String activationToken(CapturedEmail message) {
        assertThat(message.content().startsWith("http://localhost:15173/activate?token=")).isTrue();
        String token = UriComponentsBuilder.fromUriString(message.content())
                .build()
                .getQueryParams()
                .getFirst("token");
        assertThat(token).isNotBlank();
        return token;
    }

    private CapturedEmail readCapture(Path file) {
        try {
            return objectMapper.readValue(Files.readAllBytes(file), CapturedEmail.class);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private static Path createCaptureDirectory() {
        try {
            // E2eCaptureEmailService requires the capture directory itself to be
            // private, so let it create the leaf directory under a temp parent.
            Path parent = Files.createTempDirectory("evalora-e2e-capture-");
            return parent.resolve("messages");
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}
