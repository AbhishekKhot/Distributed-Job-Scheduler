package dev.kairos.api.signing;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Typed request-signing overlap and bounded background cleanup settings. */
@Validated
@ConfigurationProperties("kairos.api.signing")
public record SigningProperties(
        @NotNull @DefaultValue("24h") Duration overlap,
        @NotNull @DefaultValue("60s") Duration pruneInterval,
        @Min(1) @Max(1000) @DefaultValue("100") int pruneBatchSize) {
    @AssertTrue(message = "signing durations must be positive and bounded") public boolean isDurationsValid() {
        return overlap != null
                && pruneInterval != null
                && !overlap.isNegative()
                && !overlap.isZero()
                && overlap.compareTo(Duration.ofDays(7)) <= 0
                && pruneInterval.compareTo(Duration.ofSeconds(1)) >= 0
                && pruneInterval.compareTo(Duration.ofDays(1)) <= 0;
    }
}
