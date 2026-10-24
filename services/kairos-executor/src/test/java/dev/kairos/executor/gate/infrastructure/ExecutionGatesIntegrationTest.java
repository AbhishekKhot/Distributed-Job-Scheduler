package dev.kairos.executor.gate.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.run.Fence;
import dev.kairos.engine.lock.AcquireResult;
import dev.kairos.engine.lock.RedisConnector;
import dev.kairos.engine.lock.RedisProperties;
import dev.kairos.engine.lock.RemainingResult;
import dev.kairos.engine.lock.RunLock;
import dev.kairos.engine.lock.RunLockPort;
import dev.kairos.engine.lock.SentMarker;
import dev.kairos.engine.lock.SentMarkerPort;
import dev.kairos.engine.outbox.CellTransactionGuard;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.executor.gate.application.ExecutionContext;
import dev.kairos.executor.gate.application.ExecutionGateChain;
import dev.kairos.executor.gate.application.RunClaimPort;
import dev.kairos.executor.lifecycle.infrastructure.JdbcDrainRequeuer;
import dev.kairos.http.client.SendContext;
import dev.kairos.http.client.SendVetoedException;
import dev.kairos.http.request.HttpMethod;
import dev.kairos.persistence.cell.CellRange;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.cell.CellStatus;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.proto.v1.TargetType;
import dev.kairos.testsupport.Awaits;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.KairosRedis;
import dev.kairos.testsupport.TestIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Execution locks, fenced claims, send vetoes and cancellation against the shared Redis and PostgreSQL containers. */
@KairosIntegrationTest(kafka = false)
class ExecutionGatesIntegrationTest {

    private static final SimpleMeterRegistry METERS = new SimpleMeterRegistry();
    private static final Clock CLOCK = Clock.systemUTC();
    private static JdbcClient jdbc;
    private static CellRouter cells;

    private RedisConnector connector;
    private RunLockPort locks;
    private SentMarkerPort markers;
    private RunClaimPort claims;
    private ScheduledExecutorService scheduler;

    @BeforeAll
    static void setUpDatabase(KairosPostgres postgres) {
        Database cellDatabase =
                Database.of("cell-0", postgres.cellOwnerDataSource(0), DatabaseOptions.defaults(), METERS);
        jdbc = cellDatabase.jdbc();
        jdbc.sql("CREATE SCHEMA IF NOT EXISTS scheduler").update();
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS scheduler.run_inflight (
                  shard_id smallint NOT NULL, run_id uuid PRIMARY KEY, team_id uuid NOT NULL,
                  target_id uuid NOT NULL, endpoint_id uuid NOT NULL, endpoint_version integer NOT NULL,
                  attempt smallint NOT NULL DEFAULT 1, infra_attempt smallint NOT NULL DEFAULT 0, fence integer NOT NULL,
                  state smallint NOT NULL, due_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  deferrals integer NOT NULL DEFAULT 0, owner_node text, lease_until timestamptz,
                  cancel_requested boolean NOT NULL)
                """).update();
        jdbc.sql("ALTER TABLE scheduler.run_inflight ADD COLUMN IF NOT EXISTS attempt smallint NOT NULL DEFAULT 1")
                .update();
        jdbc.sql(
                        "ALTER TABLE scheduler.run_inflight ADD COLUMN IF NOT EXISTS infra_attempt smallint NOT NULL DEFAULT 0")
                .update();
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS scheduler.outbox (
                  shard_id smallint NOT NULL, outbox_id uuid PRIMARY KEY, topic smallint NOT NULL,
                  msg_key bytea NOT NULL, payload bytea NOT NULL, trace_parent text, run_id uuid,
                  available_at timestamptz NOT NULL, created_at timestamptz NOT NULL)
                """).update();
        CellRange range = new CellRange(0, 0, 255, "cell-0", CellStatus.ACTIVE);
        cells = new CellRouter(() -> List.of(range), Map.of("cell-0", cellDatabase), Duration.ofMinutes(5), CLOCK);
    }

    @BeforeEach
    void setUp(KairosRedis redis) {
        RedisProperties properties = RedisProperties.of(redis.host(), redis.port(), redis.username(), redis.password());
        connector = RedisConnector.create(properties, METERS, CLOCK);
        connector.connect();
        Awaits.until(() -> connector.topologyProbe().read().isPresent());
        locks = connector.runLock("executor-a");
        markers = connector.sentMarkers();
        claims = new JdbcRunClaim(cells);
        scheduler = Executors.newScheduledThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        connector.close();
    }

    @Test
    void oneOfOneThousandConcurrentDuplicateDispatchesReachesTheSendObserver() throws Exception {
        Dispatch dispatch = dispatch();
        seed(dispatch, 4, false);
        RunLockPort secondNode = connector.runLock("executor-b");
        ExecutionGateChain firstChain = chain(locks, claims, markers);
        ExecutionGateChain secondChain = chain(secondNode, claims, markers);
        AtomicInteger calls = new AtomicInteger();
        var first = processor(firstChain, locks, claims, (context, observer) -> {
            observer.beforeSend(new SendContext(HttpMethod.POST, "jobs.example.test", 443));
            calls.incrementAndGet();
        });
        var second = processor(secondChain, secondNode, claims, (context, observer) -> {
            observer.beforeSend(new SendContext(HttpMethod.POST, "jobs.example.test", 443));
            calls.incrementAndGet();
        });
        CountDownLatch ready = new CountDownLatch(1_000);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();

        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1_000; i++) {
                var processor = i % 2 == 0 ? first : second;
                tasks.add(workers.submit(() -> {
                    ready.countDown();
                    start.await();
                    processor.process(dispatch);
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var task : tasks) {
                task.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(calls).hasValue(1);
        assertThat(readState(dispatch)).isEqualTo(3);
        assertThat(readFence(dispatch)).isEqualTo(5);
        assertThat(METERS.counter(ExecutionGateChain.METRIC, "gate", "run_lock", "outcome", "rejected")
                                .count()
                        + METERS.counter(ExecutionGateChain.METRIC, "gate", "fenced_claim", "outcome", "rejected")
                                .count())
                .isGreaterThan(0);
    }

    @Test
    void cancelledDispatchCannotReachTheSendObserver() throws Exception {
        Dispatch dispatch = dispatch();
        seed(dispatch, 4, true);
        AtomicInteger calls = new AtomicInteger();
        var processor = processor(chain(locks, claims, markers), locks, claims, (context, observer) -> {
            observer.beforeSend(new SendContext(HttpMethod.POST, "jobs.example.test", 443));
            calls.incrementAndGet();
        });

        processor.process(dispatch);

        assertThat(calls).hasValue(0);
        assertThat(readState(dispatch)).isEqualTo(2);
        assertThat(readFence(dispatch)).isEqualTo(4);
    }

    @Test
    void drainRequeueAdvancesTheFenceAndWritesDispatchOutboxAtomically() {
        Dispatch dispatch = dispatch();
        seed(dispatch, 4, false);
        jdbc.sql(
                        "UPDATE scheduler.run_inflight SET state = 3, fence = 5, owner_node = 'executor-a' WHERE run_id = :run")
                .param("run", uuid(dispatch.getRunId()))
                .update();
        OutboxWriter writer = new OutboxWriter(
                new CellTransactionGuard(cells), new KairosIdFactory(CLOCK, new SecureRandom()), CLOCK);
        RunId runId = new RunId(uuid(dispatch.getRunId()));
        RunLock lock = ((AcquireResult.Acquired) locks.acquire(runId, new Fence(5), Duration.ofSeconds(30))).lock();
        JdbcDrainRequeuer requeuer = new JdbcDrainRequeuer(cells, writer, locks, CLOCK);

        assertThat(requeuer.requeue(dispatch, 5, "executor-a", lock)).isTrue();
        assertThat(readState(dispatch)).isEqualTo(2);
        assertThat(readFence(dispatch)).isEqualTo(6);
        assertThat(jdbc.sql("SELECT payload FROM scheduler.outbox WHERE run_id = :run")
                        .param("run", uuid(dispatch.getRunId()))
                        .query(byte[].class)
                        .single())
                .containsExactly(dispatch.toBuilder().setFence(6).build().toByteArray());
        assertThat(requeuer.requeue(dispatch, 5, "executor-a", lock)).isFalse();
        assertThat(locks.remaining(lock)).isInstanceOf(RemainingResult.NotOwned.class);
        assertThat(jdbc.sql("SELECT count(*) FROM scheduler.outbox WHERE run_id = :run")
                        .param("run", uuid(dispatch.getRunId()))
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void anExecutorPausedPastTheRedisTtlIsVetoedBeforeTheSentMarker() throws Exception {
        Dispatch dispatch = dispatch();
        RunId runId = new RunId(uuid(dispatch.getRunId()));
        Duration timeout = Duration.ofMillis(100);
        Duration shortTtl = Duration.ofMillis(250);
        ExecutionContext context = new ExecutionContext(dispatch, runId, new Fence(2), timeout, shortTtl);
        RunLock lock = ((AcquireResult.Acquired) locks.acquire(runId, new Fence(2), shortTtl)).lock();
        context.acquired(lock);
        context.claimed(new Fence(3), CLOCK.instant().plus(shortTtl));
        ExecutionGateChain gates = chain(locks, claims, markers);
        Thread.sleep(400);

        assertThatThrownBy(() -> gates.sendObserver(context)
                        .beforeSend(new SendContext(HttpMethod.POST, "jobs.example.test", 443)))
                .isInstanceOf(SendVetoedException.class);
        assertThat(locks.remaining(lock)).isInstanceOf(RemainingResult.NotOwned.class);
        assertThat(markers.check(new SentMarker(runId, dispatch.getAttempt(), dispatch.getInfraAttempt())))
                .isEqualTo(SentMarkerPort.MarkerCheck.ABSENT);
    }

    @Test
    void aLongCallRenewsTheRedisLockAndTheMatchingCellLease() throws Exception {
        Dispatch dispatch = dispatch();
        seed(dispatch, 2, false);
        Duration timeout = Duration.ofMillis(100);
        Duration ttl = Duration.ofMillis(600);
        ExecutionContext context =
                new ExecutionContext(dispatch, new RunId(uuid(dispatch.getRunId())), new Fence(2), timeout, ttl);
        RunLock lock = ((AcquireResult.Acquired) locks.acquire(context.runId(), context.dispatchFence(), ttl)).lock();
        context.acquired(lock);
        Instant leaseUntil = CLOCK.instant().plus(ttl);
        Fence claimFence =
                claims.claim(context, lock.token().nodeId(), leaseUntil).orElseThrow();
        context.claimed(claimFence, leaseUntil);

        LeaseRenewer renewer = new LeaseRenewer(context, locks, claims, CLOCK, METERS, scheduler);
        try {
            Thread.sleep(750);
        } finally {
            renewer.close();
        }

        assertThat(locks.remaining(lock)).isInstanceOf(RemainingResult.Owned.class);
        Instant storedLease = jdbc.sql("SELECT lease_until FROM scheduler.run_inflight WHERE run_id = :run")
                .param("run", context.runId().value())
                .query((rs, _) -> rs.getTimestamp("lease_until").toInstant())
                .single();
        assertThat(storedLease).isAfter(CLOCK.instant().plus(Duration.ofMillis(300)));
        locks.release(lock);
    }

    private static ExecutionGateChain chain(RunLockPort locks, RunClaimPort claims, SentMarkerPort markers) {
        return new ExecutionGateChain(
                List.of(
                        new RunLockGate(locks),
                        new FencedClaimGate(claims, CLOCK),
                        new SendTimeGuardGate(locks),
                        new SentMarkerGate(markers)),
                METERS);
    }

    private DispatchExecutionProcessor processor(
            ExecutionGateChain gates,
            RunLockPort lockPort,
            RunClaimPort claimPort,
            dev.kairos.executor.gate.application.ExecutionAction action) {
        return new DispatchExecutionProcessor(
                gates,
                lockPort,
                claimPort,
                action,
                CLOCK,
                METERS,
                scheduler,
                new dev.kairos.executor.cancel.infrastructure.InFlightCalls());
    }

    private Dispatch dispatch() {
        RunId run = TestIds.runId(7);
        return Dispatch.newBuilder()
                .setRunId(bytes(run.value()))
                .setShard(run.shard().value())
                .setFence(4)
                .setAttempt(1)
                .setInfraAttempt(0)
                .setTeamId(bytes(TestIds.teamId().value()))
                .setTargetType(TargetType.TARGET_TYPE_JOB)
                .setDefinitionId(bytes(TestIds.jobId(7).value()))
                .setDefinitionVersion(1)
                .setTargetId(bytes(TestIds.jobId(7).value()))
                .setEndpointId(bytes(TestIds.endpointId().value()))
                .setEndpointVersion(1)
                .setPriority(8)
                .setTimeoutMs(5_000)
                .build();
    }

    private void seed(Dispatch dispatch, int fence, boolean cancelled) {
        jdbc.sql("""
                INSERT INTO scheduler.run_inflight
                  (shard_id, run_id, team_id, target_id, endpoint_id, endpoint_version, attempt,
                   infra_attempt, fence, state, due_at, deferrals, owner_node, lease_until, cancel_requested)
                VALUES (:shard, :run, :team, :target, :endpoint, :version, :attempt,
                        :infraAttempt, :fence, 2, CURRENT_TIMESTAMP, 0, NULL, NULL, :cancelled)
                """)
                .param("shard", dispatch.getShard())
                .param("run", uuid(dispatch.getRunId()))
                .param("team", uuid(dispatch.getTeamId()))
                .param("target", uuid(dispatch.getTargetId()))
                .param("endpoint", uuid(dispatch.getEndpointId()))
                .param("version", dispatch.getEndpointVersion())
                .param("attempt", dispatch.getAttempt())
                .param("infraAttempt", dispatch.getInfraAttempt())
                .param("fence", fence)
                .param("cancelled", cancelled)
                .update();
    }

    private int readState(Dispatch dispatch) {
        return jdbc.sql("SELECT state FROM scheduler.run_inflight WHERE run_id = :run")
                .param("run", uuid(dispatch.getRunId()))
                .query(Integer.class)
                .single();
    }

    private int readFence(Dispatch dispatch) {
        return jdbc.sql("SELECT fence FROM scheduler.run_inflight WHERE run_id = :run")
                .param("run", uuid(dispatch.getRunId()))
                .query(Integer.class)
                .single();
    }

    private static ByteString bytes(UUID id) {
        return ByteString.copyFrom(ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array());
    }

    private static UUID uuid(ByteString id) {
        ByteBuffer buffer = ByteBuffer.wrap(id.toByteArray());
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
