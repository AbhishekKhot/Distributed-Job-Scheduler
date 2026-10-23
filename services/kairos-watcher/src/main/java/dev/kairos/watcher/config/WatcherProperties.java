package dev.kairos.watcher.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Heartbeat, lease and polling settings for watcher instances. */
@Validated
@ConfigurationProperties("kairos.watcher")
public record WatcherProperties(
        @NotNull @DefaultValue("5s") Duration heartbeatInterval,
        @NotNull @DefaultValue("15s") Duration heartbeatStaleAfter,
        @NotNull @DefaultValue("15s") Duration leaseTtl,
        @NotNull @DefaultValue("3s") Duration stopBeforeLeaseExpiry,
        @Min(0) @Max(16) @DefaultValue("2") int retainedSurplusLeases,
        @Min(1) @Max(64) @DefaultValue("8") int shardGroupSize,
        @NotNull @DefaultValue("200ms") Duration pollJitter) {

    /** Ensures nodes refresh before expiry and local work stops before the fencing window closes. */
    public WatcherProperties {
        if (heartbeatInterval == null
                || heartbeatStaleAfter == null
                || leaseTtl == null
                || stopBeforeLeaseExpiry == null
                || pollJitter == null
                || heartbeatInterval.isNegative()
                || heartbeatInterval.isZero()
                || heartbeatStaleAfter.compareTo(heartbeatInterval) < 0
                || leaseTtl.compareTo(heartbeatInterval.multipliedBy(2)) < 0
                || heartbeatStaleAfter.compareTo(leaseTtl) > 0
                || stopBeforeLeaseExpiry.isNegative()
                || stopBeforeLeaseExpiry.compareTo(leaseTtl) >= 0
                || pollJitter.isNegative()
                || pollJitter.compareTo(heartbeatInterval) >= 0
                || retainedSurplusLeases < 0
                || retainedSurplusLeases > 16
                || shardGroupSize < 1
                || shardGroupSize > 64) {
            throw new IllegalArgumentException("Invalid watcher timing or capacity settings");
        }
    }
}
