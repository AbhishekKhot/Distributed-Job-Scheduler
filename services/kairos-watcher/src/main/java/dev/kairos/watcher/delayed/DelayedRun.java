package dev.kairos.watcher.delayed;

import dev.kairos.engine.run.InflightRun;
import java.util.Objects;

/** A claimed delayed run and its current deferral count. */
public record DelayedRun(InflightRun run, int deferrals) {
    /** Validates the claimed row. */
    public DelayedRun {
        Objects.requireNonNull(run, "run");
        if (deferrals < 0) throw new IllegalArgumentException("deferrals must not be negative");
    }
}
