package dev.kairos.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ObservabilityPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class))
            .withPropertyValues("kairos.observability.instance=api-1");

    @Test
    void defaultsToNoExportAndTenPercentSampling() {
        runner.run(context -> {
            ObservabilityProperties.Tracing tracing =
                    context.getBean(ObservabilityProperties.class).tracing();

            assertThat(tracing.exportEnabled()).isFalse();
            assertThat(tracing.endpoint()).isNull();
            assertThat(tracing.samplingProbability()).isEqualTo(0.1);
        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "kairos.observability.tracing.sampling-probability=1.5",
                "kairos.observability.tracing.sampling-probability=-0.1",
                "kairos.observability.instance=bad instance",
                "kairos.observability.cell=Cell_0",
                "kairos.observability.tracing.endpoint=file:///etc/passwd",
                "kairos.observability.tracing.export-enabled=true"
            })
    void rejectsInvalidSettingsAtStartup(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void acceptsAnHttpsEndpointWithExportEnabled() {
        ObservabilityProperties.Tracing tracing =
                new ObservabilityProperties.Tracing(true, URI.create("https://collector.internal:4318/v1/traces"), 0.5);

        assertThat(tracing.exportEnabled()).isTrue();
    }

    @Test
    void rejectsARelativeEndpoint() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ObservabilityProperties.Tracing(false, URI.create("/v1/traces"), 0.1));
    }
}
