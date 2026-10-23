package dev.kairos.domain.workflow;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.model.EndpointName;
import dev.kairos.domain.model.TaskKey;
import dev.kairos.domain.template.OutputSpec;
import dev.kairos.domain.template.RequestTemplate;
import java.util.Map;
import java.util.Objects;

/**
 * A task with endpoint outputs resolved by the API, so references can be validated without infrastructure access.
 * @param key unique task key
 * @param endpoint registered endpoint name
 * @param request request-value overrides
 * @param outputs effective output declarations from the endpoint and any validated task mapping
 */
public record WorkflowTask(
        TaskKey key, EndpointName endpoint, RequestTemplate request, Map<String, OutputSpec> outputs) {
    /** Copies declarations and checks their identifier grammar. */
    public WorkflowTask {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(request, "request");
        if (outputs.size() > 256) throw ValidationFailedException.of("outputs", FieldError.RANGE);
        for (String name : outputs.keySet()) {
            if (name.length() > 64 || !name.matches("^[A-Za-z][A-Za-z0-9_-]{0,63}$")) {
                throw ValidationFailedException.of("outputs", FieldError.PATTERN);
            }
        }
        outputs = Map.copyOf(outputs);
    }
}
