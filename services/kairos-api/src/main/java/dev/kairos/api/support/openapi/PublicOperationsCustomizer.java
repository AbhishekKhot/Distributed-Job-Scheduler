package dev.kairos.api.support.openapi;

import dev.kairos.api.security.PublicEndpoints;
import dev.kairos.api.support.problem.ApiException;
import dev.kairos.api.support.problem.Problem;
import dev.kairos.domain.error.ErrorCode;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;

/**
 * Completes the document once all operations are known: registers the {@code Problem} and {@code FieldError}
 * schemas, adds 401 {@code unauthenticated} to every operation that needs a credential, and removes the security
 * requirement from the public operations of {@link PublicEndpoints}.
 */
public final class PublicOperationsCustomizer implements GlobalOpenApiCustomizer {

    @Override
    public void customise(OpenAPI openApi) {
        Components components = openApi.getComponents();
        if (components == null) {
            components = new Components();
            openApi.setComponents(components);
        }
        ModelConverters.getInstance().read(Problem.class).forEach(components::addSchemas);
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().forEach((path, item) -> {
            for (Map.Entry<PathItem.HttpMethod, Operation> entry :
                    item.readOperationsMap().entrySet()) {
                Operation operation = entry.getValue();
                if (PublicEndpoints.isPublic(entry.getKey().name(), path)) {
                    operation.setSecurity(List.of());
                    continue;
                }
                ApiResponses responses = operation.getResponses();
                if (responses == null) {
                    responses = new ApiResponses();
                    operation.setResponses(responses);
                }
                ProblemExamples.add(
                        responses, ErrorCode.UNAUTHENTICATED, ApiException.UNAUTHENTICATED_DETAIL, "Unauthorized");
            }
        });
    }
}
