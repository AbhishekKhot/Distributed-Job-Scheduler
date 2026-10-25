package dev.kairos.state.stats.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Time and cardinality bounds for in-memory statistics. */
@ConfigurationProperties("kairos.state.stats")
public record StatsProperties(
        @DefaultValue("10s") Duration flushInterval,
        @DefaultValue("100000") int maximumBuckets) {
    public StatsProperties {
        if (flushInterval.compareTo(Duration.ofSeconds(1)) < 0
                || flushInterval.compareTo(Duration.ofMinutes(1)) > 0
                || maximumBuckets < 2
                || maximumBuckets > 1000000) throw new IllegalArgumentException("Invalid statistics settings");
    }
}
