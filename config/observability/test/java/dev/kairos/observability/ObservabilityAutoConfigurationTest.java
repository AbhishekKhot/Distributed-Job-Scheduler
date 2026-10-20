package dev.kairos.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class))
            .withPropertyValues(
                    "spring.application.name=kairos-executor", "kairos.observability.instance=executor-7f9c");

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void addsServiceInstanceAndCellTags() {
        runner.withPropertyValues("kairos.observability.cell=cell-1").run(context -> {
            MeterRegistry registry = new SimpleMeterRegistry();
            context.getBean(MeterRegistryCustomizer.class).customize(registry);

            registry.counter("kairos.fires").increment();

            assertThat(registry.get("kairos.fires").counter().getId().getTags())
                    .extracting(tag -> tag.getKey() + "=" + tag.getValue())
                    .containsExactlyInAnyOrder("service=kairos-executor", "instance=executor-7f9c", "cell=cell-1");
        });
    }

    @Test
    void leavesTheCellTagOutWhenNoCellIsConfigured() {
        runner.run(context -> {
            ObservabilityProperties properties = context.getBean(ObservabilityProperties.class);

            assertThat(ObservabilityAutoConfiguration.commonTags("kairos-api", properties))
                    .extracting(tag -> tag.getKey())
                    .containsExactly("service", "instance");
        });
    }

    @Test
    void publishesScheduleLagWithBucketsAroundTwoSeconds() {
        runner.run(context -> {
            MeterRegistry registry = new SimpleMeterRegistry();
            registry.config().meterFilter(context.getBean(MeterFilter.class));

            Timer lag = Timer.builder(ObservabilityAutoConfiguration.SCHEDULE_LAG_METER)
                    .register(registry);
            lag.record(Duration.ofMillis(1800));

            CountAtBucket[] buckets = lag.takeSnapshot().histogramCounts();
            List<Double> boundsInSeconds = Arrays.stream(buckets)
                    .map(bucket -> bucket.bucket(TimeUnit.SECONDS))
                    .toList();
            assertThat(boundsInSeconds).contains(1.5, 2.0, 2.5).doesNotHaveDuplicates();
            assertThat(Arrays.stream(buckets).filter(b -> b.bucket(TimeUnit.SECONDS) == 2.0))
                    .singleElement()
                    .extracting(CountAtBucket::count)
                    .isEqualTo(1.0);
        });
    }

    @Test
    void leavesOtherMetersUntouched() {
        MeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(ObservabilityAutoConfiguration.scheduleLagSlo(List.of(Duration.ofSeconds(2))));

        Timer other = Timer.builder("kairos.executor.call").register(registry);
        other.record(Duration.ofMillis(10));

        assertThat(other.takeSnapshot().histogramCounts()).isEmpty();
    }

    @Test
    void acceptsCustomBuckets() {
        runner.withPropertyValues("kairos.observability.schedule-lag-slo=1s,2s,4s")
                .run(context -> {
                    assertThat(context.getBean(ObservabilityProperties.class).scheduleLagSlo())
                            .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
                });
    }
}
