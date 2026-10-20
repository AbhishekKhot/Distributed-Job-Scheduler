package dev.kairos.engine.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.id.RunId;
import dev.kairos.domain.run.Fence;
import dev.kairos.testsupport.Awaits;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosRedis;
import dev.kairos.testsupport.TestIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The run lock's Lua scripts on the shared Redis container, logged in as the {@code kairos} ACL user. */
@KairosIntegrationTest(postgres = false, kafka = false)
class RunLockIntegrationTest {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Fence FENCE = new Fence(7);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private RedisConnector connector;
    private RunLockPort lock;

    @BeforeEach
    void setUp(KairosRedis redis) {
        connector = RedisLockFixtures.connector(redis, registry);
        lock = connector.runLock("executor-a");
    }

    @AfterEach
    void tearDown() {
        connector.close();
    }

    @Test
    void concurrentAcquiresYieldExactlyOneOwner() throws Exception {
        RunId runId = TestIds.runId(12);
        int contenders = 16;
        List<RunLockPort> locks = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            locks.add(connector.runLock("executor-" + i));
        }
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AcquireResult>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (RunLockPort contender : locks) {
                results.add(executor.submit(() -> {
                    start.await();
                    return contender.acquire(runId, FENCE, TTL);
                }));
            }
            start.countDown();
        }

        List<AcquireResult> outcomes = new ArrayList<>();
        for (Future<AcquireResult> result : results) {
            outcomes.add(result.get());
        }
        assertThat(outcomes)
                .filteredOn(AcquireResult.Acquired.class::isInstance)
                .hasSize(1);
        assertThat(outcomes).filteredOn(AcquireResult.Held.class::isInstance).hasSize(contenders - 1);
        AcquireResult.Acquired owner = outcomes.stream()
                .filter(AcquireResult.Acquired.class::isInstance)
                .map(AcquireResult.Acquired.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(lock.inspect(runId))
                .isEqualTo(new LockInspection.Locked(owner.lock().token().value()));
        assertThat(registry.counter(RedisMetrics.ACQUIRE, "outcome", "acquired").count())
                .isEqualTo(1);
        assertThat(registry.counter(RedisMetrics.ACQUIRE, "outcome", "held").count())
                .isEqualTo(contenders - 1);
    }

    @Test
    void acquireWritesTheTokenWithTheTtl() {
        RunId runId = TestIds.runId(3);

        RunLock owned = acquire(runId);

        assertThat(owned.key()).isEqualTo("kairos:lock:run:" + runId);
        assertThat(owned.token().fence()).isEqualTo(FENCE);
        assertThat(owned.token().nodeId()).isEqualTo("executor-a");
        LockInspection inspection = lock.inspect(runId);
        assertThat(inspection).isInstanceOf(LockInspection.Locked.class);
        assertThat(((LockInspection.Locked) inspection).token()).contains(owned.token());
        Long pttl = connector.call(commands -> commands.pttl(owned.key()));
        assertThat(pttl).isBetween(1L, TTL.toMillis());
        assertThat(lock.acquire(runId, FENCE, TTL)).isInstanceOf(AcquireResult.Held.class);
    }

    @Test
    void releaseByANonOwnerIsANoOp() {
        RunId runId = TestIds.runId(4);
        RunLock owned = acquire(runId);
        RunLock intruder = new RunLock(runId, LockToken.generate(FENCE, "executor-b", new SecureRandom()));

        assertThat(lock.release(intruder)).isEqualTo(ReleaseResult.NOT_OWNED);
        assertThat(lock.inspect(runId))
                .isEqualTo(new LockInspection.Locked(owned.token().value()));
        assertThat(lock.remaining(owned)).isInstanceOf(RemainingResult.Owned.class);

        assertThat(lock.release(owned)).isEqualTo(ReleaseResult.RELEASED);
        assertThat(lock.inspect(runId)).isEqualTo(new LockInspection.Free());
        assertThat(lock.release(owned)).isEqualTo(ReleaseResult.NOT_OWNED);
    }

    @Test
    void remainingReturnsTheTimeLeftOnlyToTheOwner() {
        RunId runId = TestIds.runId(5);
        RunLock owned = acquire(runId);
        RunLock other = new RunLock(runId, LockToken.generate(FENCE, "executor-b", new SecureRandom()));

        RemainingResult remaining = lock.remaining(owned);

        assertThat(remaining.timeLeft())
                .hasValueSatisfying(left -> assertThat(left).isPositive().isLessThanOrEqualTo(TTL));
        assertThat(lock.remaining(other)).isEqualTo(new RemainingResult.NotOwned());
        assertThat(lock.remaining(other).timeLeft()).isEmpty();
    }

    @Test
    void remainingIsEmptyAfterExpiry() {
        RunId runId = TestIds.runId(6);
        AcquireResult result = lock.acquire(runId, FENCE, Duration.ofMillis(150));
        assertThat(result).isInstanceOf(AcquireResult.Acquired.class);
        RunLock owned = ((AcquireResult.Acquired) result).lock();

        Awaits.until(() -> lock.remaining(owned) instanceof RemainingResult.NotOwned);

        assertThat(lock.remaining(owned).timeLeft()).isEmpty();
        assertThat(lock.inspect(runId)).isEqualTo(new LockInspection.Free());
        assertThat(lock.extend(owned, TTL)).isEqualTo(ExtendResult.NOT_OWNED);
        assertThat(lock.acquire(runId, FENCE, TTL)).isInstanceOf(AcquireResult.Acquired.class);
    }

    @Test
    void extendResetsTheTtlOnlyForTheOwner() {
        RunId runId = TestIds.runId(8);
        AcquireResult result = lock.acquire(runId, FENCE, Duration.ofSeconds(5));
        RunLock owned = ((AcquireResult.Acquired) result).lock();
        RunLock other = new RunLock(runId, LockToken.generate(FENCE, "executor-b", new SecureRandom()));

        assertThat(lock.extend(other, Duration.ofMinutes(5))).isEqualTo(ExtendResult.NOT_OWNED);
        assertThat(lock.remaining(owned).timeLeft())
                .hasValueSatisfying(left -> assertThat(left).isLessThanOrEqualTo(Duration.ofSeconds(5)));

        assertThat(lock.extend(owned, Duration.ofMinutes(5))).isEqualTo(ExtendResult.EXTENDED);
        assertThat(lock.remaining(owned).timeLeft())
                .hasValueSatisfying(left -> assertThat(left).isGreaterThan(Duration.ofMinutes(4)));
    }

    @Test
    void reloadsScriptsAfterRedisLosesItsScriptCache() {
        RunId runId = TestIds.runId(9);
        RunLock owned = acquire(runId);
        double unavailableBefore = registry.counter(RedisMetrics.UNAVAILABLE).count();

        String flushed = connector.call(commands -> commands.scriptFlush());
        assertThat(flushed).isEqualTo("OK");

        assertThat(lock.remaining(owned)).isInstanceOf(RemainingResult.Owned.class);
        assertThat(lock.release(owned)).isEqualTo(ReleaseResult.RELEASED);
        assertThat(registry.counter(RedisMetrics.UNAVAILABLE).count()).isEqualTo(unavailableBefore);
        assertThat(registry.timer(RedisMetrics.SCRIPT_DURATION, "script", "remaining")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void rejectsTtlsOutsideTheAllowedRange() {
        RunId runId = TestIds.runId(10);

        assertThatThrownBy(() -> lock.acquire(runId, FENCE, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lock.acquire(runId, FENCE, Duration.ofHours(2)))
                .isInstanceOf(IllegalArgumentException.class);
        RunLock owned = acquire(runId);
        assertThatThrownBy(() -> lock.extend(owned, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sentMarkersLiveFor48Hours() {
        SentMarkerPort markers = connector.sentMarkers();
        SentMarker marker = new SentMarker(TestIds.runId(11), 2, 1);

        assertThat(markers.check(marker)).isEqualTo(SentMarkerPort.MarkerCheck.ABSENT);
        assertThat(markers.mark(marker)).isEqualTo(SentMarkerPort.MarkResult.MARKED);

        assertThat(markers.check(marker)).isEqualTo(SentMarkerPort.MarkerCheck.PRESENT);
        assertThat(markers.check(new SentMarker(marker.runId(), 2, 2))).isEqualTo(SentMarkerPort.MarkerCheck.ABSENT);
        String value = connector.call(commands -> commands.get(marker.key()));
        assertThat(value).isEqualTo("1");
        Long pttl = connector.call(commands -> commands.pttl(marker.key()));
        assertThat(pttl)
                .isBetween(Duration.ofHours(47).toMillis(), Duration.ofHours(48).toMillis());
    }

    @Test
    void probeReadsThePrimarysReplicationId() {
        RedisTopologyProbe probe = connector.topologyProbe();

        RedisTopology topology = probe.read().orElseThrow();

        assertThat(topology.role()).isEqualTo(RedisTopology.Role.PRIMARY);
        assertThat(topology.replicationId()).hasSize(40);
        assertThat(probe.read()).contains(topology);
    }

    @Test
    void connectWarmsUpTheConnectionInTheBackground(KairosRedis redis) {
        try (RedisConnector warm = RedisConnector.create(
                RedisProperties.of(redis.host(), redis.port(), redis.username(), redis.password()),
                new SimpleMeterRegistry(),
                Clock.systemUTC())) {
            warm.connect();
            warm.connect();

            Awaits.until(() -> warm.topologyProbe().read().isPresent());
        }
    }

    private RunLock acquire(RunId runId) {
        AcquireResult result = lock.acquire(runId, FENCE, TTL);
        assertThat(result).isInstanceOf(AcquireResult.Acquired.class);
        assertThat(result.outcome()).isEqualTo(AcquireResult.Outcome.ACQUIRED);
        return ((AcquireResult.Acquired) result).lock();
    }
}
