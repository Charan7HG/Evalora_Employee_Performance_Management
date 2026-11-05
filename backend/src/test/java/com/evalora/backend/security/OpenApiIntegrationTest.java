package com.evalora.backend.security;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Springdoc OpenAPI / Swagger UI configuration.
 *
 * <p>Verifies that the Springdoc OpenAPI bean is configured with the expected
 * API title, version and JWT bearer security scheme. Also verifies that the
 * application context loads successfully with Springdoc enabled.</p>
 */
@SpringBootTest
class OpenApiIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired(required = false)
    private OpenAPI openApi;

    // --------------------------------------------------------- context loads

    @Test
    void applicationContextLoadsWithSpringdoc() {
        assertThat(context).isNotNull();
    }

    // --------------------------------------------------------- OpenAPI bean

    @Test
    void openApiBeanIsConfigured() {
        assertThat(openApi).isNotNull();
    }

    @Test
    void openApiHasExpectedTitleAndVersion() {
        assertThat(openApi).isNotNull();
        assertThat(openApi.getInfo().getTitle())
                .isEqualTo("Evalora Employee Performance Management System");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("1.0.0");
        assertThat(openApi.getInfo().getDescription()).isNotNull();
    }

    @Test
    void openApiHasJwtBearerSecurityScheme() {
        assertThat(openApi).isNotNull();
        var schemes = openApi.getComponents().getSecuritySchemes();
        assertThat(schemes).isNotNull();
        var bearer = schemes.get("bearerAuth");
        assertThat(bearer).isNotNull();
        assertThat(bearer.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(bearer.getScheme()).isEqualTo("bearer");
        assertThat(bearer.getBearerFormat()).isEqualTo("JWT");
    }

    @Test
    void openApiHasGlobalSecurityRequirement() {
        assertThat(openApi).isNotNull();
        assertThat(openApi.getSecurity()).isNotNull();
        assertThat(openApi.getSecurity())
                .anyMatch(req -> req.containsKey("bearerAuth"));
    }
}
