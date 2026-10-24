package dev.kairos.executor.consume.infrastructure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Dispatch consumer flow-control settings. */
@Validated
@ConfigurationProperties("kairos.executor.consumer")
public record ExecutorConsumerProperties(
        @Min(250) @Max(4000) @DefaultValue("4000") int highWater,
        @Min(0) @Max(3999) @DefaultValue("3000") int lowWater,
        @NotNull @DefaultValue("1s") Duration deferDelay,
        @NotNull @DefaultValue("100ms") Duration pollTimeout) {

    /** Validates the water marks and delays. */
    public ExecutorConsumerProperties {
        if (lowWater >= highWater
                || deferDelay.isNegative()
                || deferDelay.isZero()
                || pollTimeout.isNegative()
                || pollTimeout.isZero()) {
            throw new IllegalArgumentException("Invalid executor consumer water marks or durations");
        }
    }
}
