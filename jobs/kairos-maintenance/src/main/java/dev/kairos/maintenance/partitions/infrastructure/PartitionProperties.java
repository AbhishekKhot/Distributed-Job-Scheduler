package dev.kairos.maintenance.partitions.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bounded DDL waits and fallback-row purge batches; retention and the three-day horizon are fixed contracts. */
@ConfigurationProperties("kairos.maintenance.partitions")
public record PartitionProperties(
        @DefaultValue("2s") Duration lockTimeout,
        @DefaultValue("30s") Duration statementTimeout,
        @DefaultValue("2m") Duration transactionTimeout,
        @DefaultValue("2000") int purgeBatchSize) {
    public PartitionProperties {
        if (lockTimeout.toMillis() < 1
                || lockTimeout.compareTo(Duration.ofMinutes(1)) > 0
                || statementTimeout.compareTo(lockTimeout) < 0
                || statementTimeout.compareTo(Duration.ofMinutes(10)) > 0
                || transactionTimeout.compareTo(statementTimeout) < 0
                || transactionTimeout.compareTo(Duration.ofMinutes(30)) > 0
                || purgeBatchSize < 1
                || purgeBatchSize > 10000) throw new IllegalArgumentException("Invalid partition maintenance limits");
    }
}
