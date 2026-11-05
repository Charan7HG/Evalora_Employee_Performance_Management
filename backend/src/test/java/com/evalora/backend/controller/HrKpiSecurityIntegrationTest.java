package com.evalora.backend.controller;

import com.evalora.backend.entity.User;
import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.repository.KpiMasterRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HR-only authorization for the KPI catalogue. MANAGER and EMPLOYEE must be
 * rejected even before the target KPI is looked up.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_kpi_security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class HrKpiSecurityIntegrationTest {

    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(12).encode("KpiSecurity@2026");
    private static final String VALID_BODY = "{\"name\":\"Security KPI\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KpiMasterRepository kpiRepository;

    @Autowired
    private JwtService jwtService;

    private String hrToken;
    private String managerToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        kpiRepository.deleteAll();
        userRepository.deleteAll();

        hrToken = jwtService.generateToken(userRepository.saveAndFlush(user("hr.kpi.security@evalora.local", Role.HR)));
        managerToken = jwtService.generateToken(
                userRepository.saveAndFlush(user("manager.kpi.security@evalora.local", Role.MANAGER)));
        employeeToken = jwtService.generateToken(
                userRepository.saveAndFlush(user("employee.kpi.security@evalora.local", Role.EMPLOYEE)));
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/hr/kpis"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(post("/api/hr/kpis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/hr/kpis/1/deactivate"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void employeesCannotUseAnyKpiManagementEndpoint() throws Exception {
        assertForbiddenWith(employeeToken);
    }

    @Test
    void managersCannotUseAnyKpiManagementEndpoint() throws Exception {
        assertForbiddenWith(managerToken);
    }

    @Test
    void hrCanUseTheKpiEndpoints() throws Exception {
        mockMvc.perform(authed(get("/api/hr/kpis"), hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(authed(post("/api/hr/kpis"), hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Security KPI"));

        mockMvc.perform(authed(get("/api/hr/kpis/999999"), hrToken))
                .andExpect(status().isNotFound());
    }

    private void assertForbiddenWith(String token) throws Exception {
        mockMvc.perform(authed(get("/api/hr/kpis"), token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(authed(get("/api/hr/kpis/1"), token))
                .andExpect(status().isForbidden());

        mockMvc.perform(authed(post("/api/hr/kpis"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());

        mockMvc.perform(authed(put("/api/hr/kpis/1"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());

        mockMvc.perform(authed(patch("/api/hr/kpis/1/deactivate"), token))
                .andExpect(status().isForbidden());

        mockMvc.perform(authed(patch("/api/hr/kpis/1/reactivate"), token))
                .andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
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
