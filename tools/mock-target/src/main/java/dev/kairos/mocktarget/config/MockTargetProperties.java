package dev.kairos.mocktarget.config;

import jakarta.validation.constraints.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Bounded benchmark configuration. Verification keys never appear in diagnostics. */
@Validated
@ConfigurationProperties("kairos.mock-target")
public record MockTargetProperties(
        @Min(1) @Max(50_000_000) @DefaultValue("1000000") int maxKeys,
        @Min(0) @Max(1_048_576) @DefaultValue("1048576") int maxBodyBytes,
        @NotNull @DefaultValue("30s") Duration snapshotInterval,

        @NotNull @DefaultValue("/tmp/kairos-mock-target/stats.json")
        Path snapshotPath,

        @NotNull @DefaultValue List<String> verificationKeys) {
    public MockTargetProperties {
        if (snapshotInterval == null
                || snapshotInterval.compareTo(Duration.ofSeconds(1)) < 0
                || snapshotInterval.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("Snapshot interval must be between 1s and 1h.");
    }

    @Override
    public String toString() {
        return "MockTargetProperties[maxKeys=" + maxKeys + ", maxBodyBytes=" + maxBodyBytes + ", snapshotInterval="
                + snapshotInterval + ", verificationKeys=<redacted>]";
    }
}
