package dev.kairos.mocktarget;

import static org.assertj.core.api.Assertions.*;

import dev.kairos.mocktarget.behavior.application.CallTracker;
import dev.kairos.mocktarget.behavior.infrastructure.InMemoryCallTracker;
import dev.kairos.mocktarget.behavior.infrastructure.SnapshotWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class CallTrackerTest {
    @TempDir
    Path directory;

    @Test
    void countsDuplicatesExactlyAcrossTenThousandConcurrentCalls() throws Exception {
        var tracker = new InMemoryCallTracker(1000, Clock.systemUTC());
        try (var tasks = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 10000; i++) {
                int key = i % 500;
                futures.add(tasks.submit(() -> tracker.record("run-" + key, true)));
            }
            for (var result : futures) result.get();
        }
        var stats = tracker.stats();
        assertThat(stats.totalCalls()).isEqualTo(10000);
        assertThat(stats.uniqueKeys()).isEqualTo(500);
        assertThat(stats.duplicateKeys()).isEqualTo(500);
        assertThat(stats.duplicateCalls()).isEqualTo(9500);
        assertThat(stats.duplicateEffects()).isEqualTo(9500);
        assertThat(stats.trackingComplete()).isTrue();
        assertThat(tracker.snapshot().counts().values()).allMatch(count -> count == 20);
        assertThat(tracker.snapshot().effectCounts().values()).allMatch(count -> count == 20);
    }

    @Test
    void idempotentCallsTrackRedeliveryWithoutRepeatingTheEffect() {
        var tracker = new InMemoryCallTracker(10, Clock.systemUTC());

        assertThat(tracker.record("run", true, true)).isEqualTo(CallTracker.RecordResult.FIRST);
        assertThat(tracker.record("run", true, true)).isEqualTo(CallTracker.RecordResult.DUPLICATE);

        assertThat(tracker.stats().duplicateCalls()).isEqualTo(1);
        assertThat(tracker.stats().duplicateEffects()).isZero();
        assertThat(tracker.snapshot().counts()).containsEntry("run", 2L);
        assertThat(tracker.snapshot().effectCounts()).containsEntry("run", 1L);
    }

    @Test
    void capacityNeverEvictsExistingKeysOrConcealsAnIncompleteRun() {
        var tracker = new InMemoryCallTracker(1, Clock.systemUTC());
        assertThat(tracker.record("first", true)).isTrue();
        assertThat(tracker.record("second", true)).isFalse();
        assertThat(tracker.record("first", true)).isTrue();
        assertThat(tracker.stats().capacityRejected()).isEqualTo(1);
        assertThat(tracker.stats().trackingComplete()).isFalse();
        assertThat(tracker.stats().duplicateCalls()).isEqualTo(1);
        assertThat(tracker.snapshot().counts()).containsOnlyKeys("first");
        assertThat(tracker.snapshot().effectCounts()).containsOnlyKeys("first");
    }

    @Test
    void countsSignatureFailuresAndUnkeyedCallsExplicitly() {
        var tracker = new InMemoryCallTracker(10, Clock.systemUTC());
        tracker.record("", false);
        tracker.record("key", false);
        tracker.record("key", true);
        assertThat(tracker.stats().signatureFailures()).isEqualTo(2);
        assertThat(tracker.stats().unkeyedCalls()).isEqualTo(1);
        assertThat(tracker.stats().trackingComplete()).isFalse();
    }

    @Test
    void snapshotsRemainCoherentWhileRequestsAreArriving() throws Exception {
        var tracker = new InMemoryCallTracker(1000, Clock.systemUTC());
        try (var tasks = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = tasks.submit(() -> {
                for (int i = 0; i < 10000; i++) tracker.record("run-" + (i % 100), true);
            });
            for (int i = 0; i < 20; i++) {
                var snapshot = tracker.snapshot();
                assertThat(snapshot.counts().values().stream()
                                .mapToLong(Long::longValue)
                                .sum())
                        .isEqualTo(snapshot.stats().totalCalls());
                assertThat(snapshot.counts().size()).isEqualTo(snapshot.stats().uniqueKeys());
                assertThat(snapshot.effectCounts()).isEqualTo(snapshot.counts());
            }
            writer.get();
        }
    }

    @Test
    void replacesSnapshotAtomicallyWithCountsAndInstanceIdentity() throws Exception {
        var tracker = new InMemoryCallTracker(10, Clock.systemUTC());
        var path = directory.resolve("snapshots/stats.json");
        var writer = new SnapshotWriter(tracker, path);
        tracker.record("first", true);
        writer.write();
        tracker.record("first", true);
        writer.write();
        var json = JsonMapper.builder().build().readTree(path.toFile());
        assertThat(json.path("version").asInt()).isEqualTo(2);
        assertThat(json.path("counts").path("first").asLong()).isEqualTo(2);
        assertThat(json.path("stats").path("instanceId").asString())
                .isEqualTo(tracker.stats().instanceId());
        assertThat(json.path("effectCounts").path("first").asLong()).isEqualTo(2);
        try (var files = Files.list(path.getParent())) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void snapshotIoFailureIsCountedAndDoesNotStopTracking() throws Exception {
        var tracker = new InMemoryCallTracker(10, Clock.systemUTC());
        var file = directory.resolve("file");
        Files.writeString(file, "occupied");
        new SnapshotWriter(tracker, file.resolve("stats.json")).write();
        tracker.record("run", true);
        assertThat(tracker.stats().snapshotFailures()).isEqualTo(1);
        assertThat(tracker.stats().totalCalls()).isEqualTo(1);
    }
}
