package dev.kairos.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class ObservabilityEnvironmentPostProcessorTest {

    private final ObservabilityEnvironmentPostProcessor processor = new ObservabilityEnvironmentPostProcessor();

    @Test
    void setsSafeDefaults() {
        StandardEnvironment environment = new StandardEnvironment();

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("logging.structured.json.customizer"))
                .isEqualTo(RedactingLogCustomizer.class.getName());
        assertThat(environment.getProperty("management.tracing.export.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("management.tracing.sampling.probability"))
                .isEqualTo("0.1");
        assertThat(environment.getProperty("management.tracing.propagation.type"))
                .isEqualTo("w3c");
        assertThat(environment.getProperty("management.logging.export.otlp.enabled"))
                .isEqualTo("false");
        assertThat(environment.getProperty("management.otlp.metrics.export.enabled"))
                .isEqualTo("false");
        assertThat(environment.containsProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
                .isFalse();
        assertThat(environment.getProperty("kairos.observability.instance")).isNotBlank();
    }

    @Test
    void followsTheKairosTracingSettings() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "test",
                        Map.of(
                                "kairos.observability.tracing.export-enabled", "true",
                                "kairos.observability.tracing.sampling-probability", "1.0",
                                "kairos.observability.tracing.endpoint", "http://otel-collector:4318/v1/traces")));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.tracing.export.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("management.tracing.sampling.probability"))
                .isEqualTo("1.0");
        assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
                .isEqualTo("http://otel-collector:4318/v1/traces");
    }

    @Test
    void neverOverridesExplicitConfiguration() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("test", Map.of("management.tracing.propagation.type", "b3")));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.tracing.propagation.type"))
                .isEqualTo("b3");
        assertThat(environment.getPropertySources().stream().reduce((first, second) -> second))
                .get()
                .extracting(source -> source.getName())
                .isEqualTo(ObservabilityEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
    }

    @Test
    void runsAfterTheConfigurationFilesAreLoaded() {
        assertThat(processor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }
}
