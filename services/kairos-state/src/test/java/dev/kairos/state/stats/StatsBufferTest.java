package dev.kairos.state.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.model.Outcome;
import dev.kairos.domain.model.Priority;
import dev.kairos.domain.model.RunState;
import dev.kairos.domain.model.TargetType;
import dev.kairos.domain.model.Trigger;
import dev.kairos.domain.run.Fence;
import dev.kairos.engine.run.InflightRun;
import dev.kairos.proto.v1.Result;
import dev.kairos.state.settle.application.SettlementBatch.Settled;
import dev.kairos.state.stats.application.StatsBuffer;
import dev.kairos.state.stats.infrastructure.StatsAggregator;
import dev.kairos.state.stats.infrastructure.StatsProperties;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.TestIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StatsBufferTest {
    private final MutableClock clock = MutableClock.at(Instant.parse("2026-10-25T12:00:00Z"));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final StatsBuffer buffer = new StatsBuffer(clock, shard -> shard / 128, 100, meters);

    private Settled event(Outcome outcome, boolean deferred, boolean retryable) {
        var job = TestIds.jobId(12);
        var at = clock.instant();
        var run = new InflightRun(
                TestIds.runId(12),
                TestIds.teamId(),
                TargetType.JOB,
                job.value(),
                1,
                job.value(),
                null,
                TestIds.endpointId(),
                1,
                at,
                at,
                RunState.RUNNING,
                1,
                1,
                at,
                0,
                new Fence(2),
                0,
                Priority.of(5),
                Trigger.SCHEDULE,
                null);
        return new Settled(
                run,
                Result.newBuilder()
                        .setLatencyMs(37)
                        .setError("must-not-be-retained")
                        .build(),
                outcome,
                deferred,
                retryable);
    }

    @Test
    void snapshotsAreCumulativeImmutableAndAcknowledgeOnlyWrittenIncrements() {
        var settled = event(Outcome.SUCCEEDED, false, false);
        buffer.record(List.of(settled));
        var first = buffer.snapshot();
        buffer.record(List.of(settled));
        assertThat(first).allSatisfy(s -> assertThat(s.counts().attempts()).isEqualTo(1));
        buffer.acknowledged(first);
        var second = buffer.snapshot();
        assertThat(second)
                .hasSize(2)
                .allSatisfy(s -> assertThat(s.counts().attempts()).isEqualTo(2));
        buffer.acknowledged(second);
        assertThat(buffer.snapshot()).isEmpty();
        assertThat(first.toString()).doesNotContain("must-not-be-retained");
    }

    @Test
    void capacityRejectsEndpointAndTeamPairTogetherAndKeepsExistingBucketsUsable() {
        var limited = new StatsBuffer(clock, shard -> 0, 2, new SimpleMeterRegistry());
        var first = event(Outcome.SUCCEEDED, false, false);
        limited.record(List.of(first, event(Outcome.DEAD_LETTERED, false, false), first));
        assertThat(limited.size()).isEqualTo(2);
        assertThat(limited.snapshot())
                .allSatisfy(s -> assertThat(s.counts().attempts()).isEqualTo(2));
    }

    @Test
    void currentAndPreviousMinutesAreRetainedAndClockRollbackCannotOverwriteAnEvictedMinute() {
        var settled = event(Outcome.SUCCEEDED, false, false);
        buffer.record(List.of(settled));
        clock.advance(Duration.ofMinutes(1));
        buffer.record(List.of(settled));
        assertThat(buffer.size()).isEqualTo(4);
        clock.advance(Duration.ofMinutes(1));
        buffer.record(List.of(settled));
        assertThat(buffer.size()).isEqualTo(4);
        assertThat(buffer.snapshot())
                .allSatisfy(s -> assertThat(s.key().minute()).isAfter(Instant.parse("2026-10-25T12:00:00Z")));
        clock.set(Instant.parse("2026-10-25T11:59:00Z"));
        buffer.record(List.of(settled));
        assertThat(buffer.size()).isEqualTo(4);
        assertThat(meters.counter("kairos.state.stats.evictions", "reason", "unflushed")
                        .count())
                .isEqualTo(2);
    }

    @Test
    void deferredResultsDoNotCreateAttemptOrLatencyBuckets() {
        buffer.record(List.of(event(null, true, false)));
        assertThat(buffer.snapshot()).isEmpty();
        assertThat(buffer.size()).isZero();
    }

    @Test
    void emptyFlushAndFailedFlushBothExpireOldMinutes() {
        var aggregate = new StatsAggregator(
                "node",
                buffer,
                (n, s) -> {
                    throw new IllegalStateException("unavailable");
                },
                meters);
        assertThat(aggregate.flush()).isZero();
        buffer.record(List.of(event(Outcome.SUCCEEDED, false, false)));
        assertThatThrownBy(aggregate::flush).isInstanceOf(IllegalStateException.class);
        assertThat(buffer.size()).isEqualTo(2);
        clock.advance(Duration.ofMinutes(2));
        assertThatThrownBy(aggregate::flush).isInstanceOf(IllegalStateException.class);
        assertThat(buffer.size()).isZero();
        assertThat(meters.counter("kairos.state.stats.evictions", "reason", "unflushed")
                        .count())
                .isEqualTo(2);
    }

    @Test
    void flushDoesNotBlockRecordAndConcurrentIncrementsSurviveAcknowledgement() throws Exception {
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        var aggregate = new StatsAggregator(
                "node",
                buffer,
                (node, snapshots) -> {
                    writing.countDown();
                    try {
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                },
                meters);
        var event = event(Outcome.SUCCEEDED, false, false);
        aggregate.committed(List.of(event));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var flushing = executor.submit(aggregate::flush);
            assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
            var recording = executor.submit(() -> aggregate.committed(List.of(event)));
            recording.get(2, TimeUnit.SECONDS);
            release.countDown();
            flushing.get(5, TimeUnit.SECONDS);
        }
        assertThat(buffer.snapshot())
                .hasSize(2)
                .allSatisfy(s -> assertThat(s.counts().attempts()).isEqualTo(2));
        aggregate.flush();
        assertThat(buffer.snapshot()).isEmpty();
    }

    @Test
    void shutdownFlushesAfterConsumerAndScheduledFailuresRemainRetryable() {
        var calls = new AtomicInteger();
        var aggregate = new StatsAggregator(
                "node",
                buffer,
                (node, snapshots) -> {
                    if (calls.getAndIncrement() == 0) throw new IllegalStateException("temporary");
                },
                meters);
        aggregate.scheduled();
        assertThat(calls.get()).isZero();
        aggregate.start();
        assertThat(aggregate.isRunning()).isTrue();
        aggregate.committed(List.of(event(Outcome.DEAD_LETTERED, false, true)));
        aggregate.scheduled();
        assertThat(meters.counter("kairos.state.stats.flush.failures").count()).isEqualTo(1);
        assertThat(buffer.snapshot()).hasSize(2);
        assertThat(aggregate.getPhase()).isLessThan(Integer.MAX_VALUE - 100);
        aggregate.stop();
        assertThat(aggregate.isRunning()).isFalse();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(buffer.snapshot()).isEmpty();
    }

    @Test
    void settingsAndIdentityHaveHardBounds() {
        assertThat(new StatsProperties(Duration.ofSeconds(10), 100000).maximumBuckets())
                .isEqualTo(100000);
        for (int size : new int[] {0, 1, 1000001})
            assertThatThrownBy(() -> new StatsProperties(Duration.ofSeconds(10), size))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StatsProperties(Duration.ZERO, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StatsProperties(Duration.ofSeconds(61), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StatsBuffer(clock, shard -> 0, 1, meters))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StatsAggregator(" ", buffer, (n, s) -> {}, meters))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StatsAggregator("n".repeat(129), buffer, (n, s) -> {}, meters))
                .isInstanceOf(IllegalArgumentException.class);
        String first = StatsAggregator.nodeId(" ", clock), second = StatsAggregator.nodeId(" ", clock);
        assertThat(first).isNotEqualTo(second).startsWith("kairos-state-");
        assertThat(StatsAggregator.nodeId("pod".repeat(100), clock).length()).isLessThanOrEqualTo(128);
    }
}
