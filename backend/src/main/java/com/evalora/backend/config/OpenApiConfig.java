package com.evalora.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI / Swagger documentation configuration.
 *
 * <p>Defines the API title, version, description and the JWT bearer security
 * scheme used by Swagger UI's Authorize button. A global bearer security
 * requirement is applied so all protected endpoints are documented as
 * requiring authentication; the three public auth endpoints override this with
 * an empty {@code @SecurityRequirement} in their {@code @Operation}.</p>
 *
 * <p>Role-based authorization ({@code ROLE_HR}, {@code ROLE_EMPLOYEE},
 * {@code ROLE_MANAGER}) is enforced by {@code SecurityConfig}; OpenAPI
 * documents the authentication requirement only.</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Evalora Employee Performance Management System")
                        .version("1.0.0")
                        .description("""
                        Evalora is a stateless JWT-secured performance management system.

                        Authentication:
                        - Obtain a token via {@code POST /api/auth/login} with email and password.
                        - Paste the returned token into the Authorize dialog as {@code Bearer <token>}.
                        - The {@code role} claim in the token is informational only; the caller's
                          role is always read from the database at request time.

                        Role-based access:
                        - {@code /api/hr/**} requires {@code ROLE_HR}.
                        - {@code /api/employee/**} requires {@code ROLE_EMPLOYEE}.
                        - {@code /api/manager/**} requires {@code ROLE_MANAGER}.
                        - {@code POST /api/auth/login}, {@code POST /api/auth/forgot-password} and
                          {@code POST /api/auth/reset-password} are public.
                        """))
                .servers(List.of(new Server().url("/").description("Local development server")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth", List.of()))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .name("bearerAuth")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
