package dev.kairos.api.config;

import dev.kairos.api.security.ApiKeyPrincipal;
import dev.kairos.api.security.KairosPrincipal;
import dev.kairos.api.security.TeamContext;
import dev.kairos.api.security.UserPrincipal;
import dev.kairos.api.support.etag.IfMatch;
import dev.kairos.api.support.openapi.CommonResponsesCustomizer;
import dev.kairos.api.support.openapi.PublicOperationsCustomizer;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI (Swagger) metadata for the public API.
 *
 * <p>Controllers document themselves with {@code @Tag}, {@code @Operation} and
 * {@code @ApiResponse}; this class only defines what applies to every operation: the API
 * description, the bearer security scheme (a user access token or a team API key), and the
 * customizers that add the common error responses and headers. Parameters that are resolved from
 * the credential ({@code TeamContext}, the principal) or from {@code If-Match} are not request
 * parameters, so they are hidden from the document.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    /** Name of the security scheme referenced by every protected operation. */
    public static final String BEARER_AUTH = "bearerAuth";

    static {
        SpringDocUtils.getConfig()
                .addRequestWrapperToIgnore(
                        TeamContext.class, KairosPrincipal.class, UserPrincipal.class, ApiKeyPrincipal.class)
                .addAnnotationsToIgnore(IfMatch.class);
    }

    /** Creates the configuration; Spring calls it. */
    public OpenApiConfig() {}

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
                // Relative, so the document is the same on every host and in the build-time export.
                .servers(List.of(new Server().url("/").description("This Kairos deployment")))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        BEARER_AUTH,
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT access token or team API key")
                                                .description(
                                                        "Access token from POST /v1/auth/login, or an API key from POST /v1/api-keys.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }

    /**
     * Adds the common error responses and the idempotency and version headers to every operation.
     *
     * @return the operation customizer
     */
    @Bean
    public CommonResponsesCustomizer commonResponsesCustomizer() {
        return new CommonResponsesCustomizer();
    }

    /**
     * Adds 401 to operations that need a credential, marks the public ones, and registers the problem schemas.
     *
     * @return the document customizer
     */
    @Bean
    public PublicOperationsCustomizer publicOperationsCustomizer() {
        return new PublicOperationsCustomizer();
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
