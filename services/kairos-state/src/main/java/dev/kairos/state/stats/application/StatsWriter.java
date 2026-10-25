package dev.kairos.state.stats.application;

import java.util.List;

/** Persists cumulative snapshots idempotently, with each cell's endpoint/team rows committed together. */
@FunctionalInterface
public interface StatsWriter {
    void write(String nodeId, List<StatsBuffer.Snapshot> snapshots);
}
