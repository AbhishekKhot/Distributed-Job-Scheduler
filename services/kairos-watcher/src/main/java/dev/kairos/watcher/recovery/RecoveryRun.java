package dev.kairos.watcher.recovery;

import dev.kairos.engine.run.InflightRun;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A locked inflight row plus executor lease fields used by recovery. */
public record RecoveryRun(
        InflightRun run,
        @Nullable String ownerNode,
        @Nullable Instant leaseUntil) {
    /** Validates the claimed row. */
    public RecoveryRun {
        Objects.requireNonNull(run, "run");
    }
}
