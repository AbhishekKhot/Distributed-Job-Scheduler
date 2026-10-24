package dev.kairos.watcher.outbox;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Optional process-wide cap for the outbox relay; zero disables the cap. */
@Validated
@ConfigurationProperties("kairos.watcher.outbox")
public record OutboxRelayProperties(
        @Min(0) @Max(1_000_000) @DefaultValue("0") int maxDispatchesPerSecond) {}
