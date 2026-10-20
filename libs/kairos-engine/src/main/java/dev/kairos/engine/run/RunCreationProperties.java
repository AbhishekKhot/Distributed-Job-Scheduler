package dev.kairos.engine.run;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Run creation settings, bound from {@code kairos.engine.runs.*}.
 *
 * @param dispatchHorizon how far ahead a scheduled run may be due and still be created {@code QUEUED} with its dispatch
 *     row; it must match the fire poller's look-ahead (LLD section 4.1, 5 seconds by default), 0 to 60 seconds
 */
@Validated
@ConfigurationProperties("kairos.engine.runs")
public record RunCreationProperties(
        @NotNull @DefaultValue("5s") Duration dispatchHorizon) {

    /** Largest accepted dispatch horizon. */
    static final Duration MAX_DISPATCH_HORIZON = Duration.ofSeconds(60);

    /**
     * Validates the horizon, which Bean Validation cannot range-check on a {@link Duration}.
     *
     * @throws IllegalArgumentException if the horizon is negative or longer than 60 seconds
     */
    public RunCreationProperties {
        Objects.requireNonNull(dispatchHorizon, "dispatchHorizon");
        if (dispatchHorizon.isNegative() || dispatchHorizon.compareTo(MAX_DISPATCH_HORIZON) > 0) {
            throw new IllegalArgumentException("The dispatch horizon must be 0 to 60 seconds");
        }
    }
}
