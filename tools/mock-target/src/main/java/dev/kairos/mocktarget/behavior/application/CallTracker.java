package dev.kairos.mocktarget.behavior.application;

import java.time.Instant;
import java.util.Map;

/** Exact bounded tracking port shared with management endpoints and the snapshot writer. */
public interface CallTracker {
    default boolean record(String key, boolean signatureValid) {
        return record(key, signatureValid, false) != RecordResult.REJECTED;
    }

    RecordResult record(String key, boolean signatureValid, boolean idempotent);

    Stats stats();

    Snapshot snapshot();

    void snapshotFailed();

    record Stats(
            String instanceId,
            Instant startedAt,
            long totalCalls,
            long uniqueKeys,
            long duplicateKeys,
            long duplicateCalls,
            long duplicateEffects,
            long unkeyedCalls,
            long signatureFailures,
            long capacityRejected,
            boolean trackingComplete,
            long snapshotFailures) {}

    record Snapshot(
            int version, Instant capturedAt, Stats stats, Map<String, Long> counts, Map<String, Long> effectCounts) {}

    enum RecordResult {
        FIRST,
        DUPLICATE,
        REJECTED
    }
}
