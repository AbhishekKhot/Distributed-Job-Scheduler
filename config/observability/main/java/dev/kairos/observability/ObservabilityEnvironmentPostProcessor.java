package dev.kairos.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Adds the observability defaults every Kairos application shares, as the lowest-precedence
 * property source, so an application's own configuration or an environment variable always wins.
 *
 * <ul>
 *   <li>Structured logs go through {@link RedactingLogCustomizer}.
 *   <li>Tracing uses W3C {@code traceparent} propagation. Sampling and OTLP export follow
 *       {@code kairos.observability.tracing.*}; export is off unless enabled, and OpenTelemetry log
 *       log and metric export are always off (logs go to stdout, metrics are scraped by
 *       Prometheus).
 *   <li>The {@code instance} tag defaults to the host name.
 * </ul>
 *
 * <p>Registered in {@code META-INF/spring.factories}. It runs after the configuration files are
 * loaded, so it can see whether an OTLP endpoint was configured.
 */
public final class ObservabilityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Name of the property source this processor adds. */
    public static final String PROPERTY_SOURCE_NAME = "kairosObservabilityDefaults";

    static final String ENDPOINT_PROPERTY = "kairos.observability.tracing.endpoint";

    /** Creates the processor; called reflectively by Spring Boot. */
    public ObservabilityEnvironmentPostProcessor() {}

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("logging.structured.json.customizer", RedactingLogCustomizer.class.getName());
        defaults.put("kairos.observability.instance", "${HOSTNAME:local}");
        defaults.put("management.tracing.propagation.type", "w3c");
        defaults.put(
                "management.tracing.sampling.probability", "${kairos.observability.tracing.sampling-probability:0.1}");
        defaults.put("management.tracing.export.enabled", "${kairos.observability.tracing.export-enabled:false}");
        defaults.put("management.logging.export.otlp.enabled", "false");
        // Metrics are scraped by Prometheus; the OTLP meter registry the OpenTelemetry starter
        // brings would otherwise push to localhost every minute.
        defaults.put("management.otlp.metrics.export.enabled", "false");
        if (environment.containsProperty(ENDPOINT_PROPERTY)) {
            defaults.put("management.opentelemetry.tracing.export.otlp.endpoint", "${" + ENDPOINT_PROPERTY + "}");
        }
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    @Override
    public int getOrder() {
        // After ConfigDataEnvironmentPostProcessor (HIGHEST_PRECEDENCE + 10), so application.yml is
        // visible, and well before the logging system reads its settings.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
