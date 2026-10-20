package dev.kairos.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Metric conventions shared by every Kairos application (IMPLEMENTATION_CHECKLIST section 4.9):
 * the common {@code service}, {@code instance} and (where set) {@code cell} tags, and the SLO
 * buckets of the {@code kairos_schedule_lag_seconds} histogram.
 *
 * <p>Tracing itself is Spring Boot's Micrometer Tracing with the OpenTelemetry bridge, configured by
 * {@link ObservabilityEnvironmentPostProcessor}.
 */
@AutoConfiguration
@EnableConfigurationProperties(ObservabilityProperties.class)
public final class ObservabilityAutoConfiguration {

    /** Micrometer name of the schedule lag timer; Prometheus exposes it as {@code kairos_schedule_lag_seconds}. */
    public static final String SCHEDULE_LAG_METER = "kairos.schedule.lag";

    /** Creates the configuration; instantiated by Spring. */
    public ObservabilityAutoConfiguration() {}

    /**
     * Adds the common tags to every meter.
     *
     * @param properties observability settings
     * @param environment the application environment, for {@code spring.application.name}
     * @return the registry customizer
     */
    @Bean
    MeterRegistryCustomizer<MeterRegistry> kairosCommonTags(
            ObservabilityProperties properties, Environment environment) {
        List<Tag> tags = commonTags(environment.getProperty("spring.application.name", "kairos"), properties);
        return registry -> registry.config().commonTags(tags);
    }

    /**
     * Publishes the schedule lag timer as a histogram with the configured SLO buckets.
     *
     * @param properties observability settings
     * @return the meter filter
     */
    @Bean
    MeterFilter kairosScheduleLagSlo(ObservabilityProperties properties) {
        return scheduleLagSlo(properties.scheduleLagSlo());
    }

    static List<Tag> commonTags(String service, ObservabilityProperties properties) {
        List<Tag> tags = new ArrayList<>();
        tags.add(Tag.of("service", service));
        tags.add(Tag.of("instance", properties.instance()));
        String cell = properties.cell();
        if (cell != null) {
            tags.add(Tag.of("cell", cell));
        }
        return List.copyOf(tags);
    }

    static MeterFilter scheduleLagSlo(List<Duration> buckets) {
        double[] nanos = buckets.stream()
                .mapToDouble(Duration::toNanos)
                .sorted()
                .distinct()
                .toArray();
        Duration max = buckets.stream().max(Duration::compareTo).orElseThrow();
        return new MeterFilter() {
            @Override
            public @Nullable DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!id.getName().equals(SCHEDULE_LAG_METER)) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .percentilesHistogram(false)
                        .serviceLevelObjectives(nanos)
                        .minimumExpectedValue((double) Duration.ofMillis(1).toNanos())
                        .maximumExpectedValue((double) max.toNanos())
                        .build()
                        .merge(config);
            }
        };
    }
}
