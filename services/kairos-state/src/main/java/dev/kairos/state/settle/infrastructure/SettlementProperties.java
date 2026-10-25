package dev.kairos.state.settle.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bounded poll and replay configuration for result settlement. */
@ConfigurationProperties("kairos.state.settle")
public record SettlementProperties(
        @DefaultValue("kairos-state-results") String groupId,
        @DefaultValue("1000") int maxPollRecords,
        @DefaultValue("250ms") Duration pollTimeout,
        @DefaultValue("500ms") Duration retryDelay) {
    public SettlementProperties {
        if (groupId == null
                || !groupId.matches("^[A-Za-z0-9._-]{1,128}$")
                || maxPollRecords < 1
                || maxPollRecords > 1000
                || pollTimeout.isNegative()
                || pollTimeout.isZero()
                || pollTimeout.compareTo(Duration.ofSeconds(5)) > 0
                || retryDelay.isNegative()
                || retryDelay.isZero()
                || retryDelay.compareTo(Duration.ofSeconds(10)) > 0)
            throw new IllegalArgumentException("Invalid result consumer settings");
    }
}
