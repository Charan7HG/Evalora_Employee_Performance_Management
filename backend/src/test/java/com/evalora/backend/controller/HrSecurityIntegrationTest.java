package com.evalora.backend.controller;

import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.repository.PasswordResetTokenRepository;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HR-only authorization and a regression check that the existing auth
 * endpoints are unaffected by the new role rule.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_hr_security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class HrSecurityIntegrationTest {

    private static final String PASSWORD = "Security@2026";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private String hrToken;
    private String managerToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        hrToken = jwtService.generateToken(userRepository.saveAndFlush(user("hr@evalora.local", Role.HR)));
        managerToken = jwtService.generateToken(
                userRepository.saveAndFlush(user("manager@evalora.local", Role.MANAGER)));
        employeeToken = jwtService.generateToken(
                userRepository.saveAndFlush(user("employee@evalora.local", Role.EMPLOYEE)));
    }

    @Test
    void unauthenticatedHrRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/hr/employees"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(post("/api/hr/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void employeeAndManagerCannotUseHrEndpoints() throws Exception {
        mockMvc.perform(get("/api/hr/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(get("/api/hr/managers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/hr/employees/1/manager")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"managerId\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void hrCanUseHrEndpoints() throws Exception {
        mockMvc.perform(get("/api/hr/employees")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/hr/managers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + hrToken))
                .andExpect(status().isOk());
    }

    @Test
    void existingAuthAndPasswordEndpointsStillBehaveTheSame() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("employee@evalora.local"));

        mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "currentPassword", PASSWORD,
                                "newPassword", "Changed@2026",
                                "confirmPassword", "Changed@2026"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "employee@evalora.local"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resetToken").doesNotExist());
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private static User user(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(PASSWORD_HASH);
        user.setRole(role);
        user.setEnabled(true);
        return user;
    }
}
