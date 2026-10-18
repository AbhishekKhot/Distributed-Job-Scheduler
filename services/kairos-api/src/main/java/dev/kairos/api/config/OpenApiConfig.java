package dev.kairos.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI (Swagger) metadata for the public API.
 *
 * <p>Controllers document themselves with {@code @Tag}, {@code @Operation} and
 * {@code @ApiResponse}; this class only defines what applies to every operation: the API
 * description and the bearer security scheme, which accepts either a user access token or a
 * team API key.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    /** Name of the security scheme referenced by every protected operation. */
    public static final String BEARER_AUTH = "bearerAuth";

    /**
     * Builds the API description shown in Swagger UI.
     *
     * @return the OpenAPI root object
     */
    @Bean
    public OpenAPI kairosOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Kairos API")
                        .version("v1")
                        .description("""
                                Schedule calls to internal HTTP APIs as jobs or DAG workflows.
                                All team resources are scoped to the team of the caller's token or API key.
                                Errors use RFC 9457 problem details.""")
                        .license(new License().name("Internal")))
                .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT access token or team API key")
                        .description("Access token from POST /v1/auth/login, or an API key from POST /v1/api-keys.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }

    /**
     * Groups the versioned public API so internal or management paths never appear in the document.
     *
     * @return the {@code v1} API group
     */
    @Bean
    public GroupedOpenApi publicApiV1() {
        return GroupedOpenApi.builder()
                .group("v1")
                .pathsToMatch("/v1/**", "/.well-known/**")
                .build();
    }
}
