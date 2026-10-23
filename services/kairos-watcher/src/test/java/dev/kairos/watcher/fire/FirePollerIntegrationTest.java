package dev.kairos.watcher.fire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.shard.Shard;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRange;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.cell.CellStatus;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.watcher.lease.OwnedShards;
import dev.kairos.watcher.support.ShardedPoller;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Checks schedule claims, durable position changes, rollback, and outbox back-pressure against PostgreSQL. */
@KairosIntegrationTest(kafka = false, redis = false)
class FirePollerIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private static Database database;
    private static Cell cell;

    @BeforeAll
    static void createSchema(KairosPostgres postgres) {
        database = Database.of(
                "cell-0", postgres.cellOwnerDataSource(0), DatabaseOptions.defaults(), new SimpleMeterRegistry());
        JdbcClient jdbc = database.jdbc();
        jdbc.sql("CREATE SCHEMA IF NOT EXISTS scheduler").update();
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS scheduler.jobs (job_id uuid PRIMARY KEY, status text NOT NULL)
                """).update();
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS scheduler.workflows (workflow_id uuid PRIMARY KEY, status text NOT NULL)
                """).update();
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS scheduler.schedules (
                  schedule_id uuid PRIMARY KEY, team_id uuid NOT NULL, target_type smallint NOT NULL,
                  shard_id smallint NOT NULL, status smallint NOT NULL, next_fire_at timestamptz,
                  kind smallint NOT NULL, cron_expr text, timezone text NOT NULL, interval_ms bigint,
                  spread_ms integer NOT NULL, dst_policy smallint NOT NULL, misfire_policy smallint NOT NULL,
                  max_catchup smallint NOT NULL, definition_version integer NOT NULL,
                  priority smallint NOT NULL, last_fired_local timestamp)
                """).update();
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS scheduler.outbox (
                  outbox_id uuid PRIMARY KEY, available_at timestamptz NOT NULL, created_at timestamptz NOT NULL)
                """).update();
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS scheduler.fire_test_rows (
                  schedule_id uuid NOT NULL, scheduled_at timestamptz NOT NULL,
                  PRIMARY KEY (schedule_id, scheduled_at))
                """).update();
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS scheduler.fire_test_leases (
                  shard_id smallint PRIMARY KEY, owner_node text NOT NULL, generation bigint NOT NULL)
                """).update();
        cell = new Cell(new CellRange(0, 0, 255, "cell-0", CellStatus.ACTIVE), database);
    }

    @Test
    void oneTimeFiresOnceAndDeletesOnlyAfterRunCreationCommits() {
        UUID id = seed(1, NOW.plusSeconds(2), 1);
        AtomicInteger created = new AtomicInteger();
        try (Harness harness = harness((currentCell, schedule, fires) -> {
            for (var fire : fires) {
                created.addAndGet(currentCell
                        .jdbc()
                        .sql("INSERT INTO scheduler.fire_test_rows VALUES (:id, :at) ON CONFLICT DO NOTHING")
                        .param("id", schedule.id())
                        .param("at", Timestamp.from(fire.at()))
                        .update());
            }
            return fires.size();
        })) {
            cell.transactions().execute(jdbc -> {
                harness.poller().poll(cell, 0);
                return null;
            });
            cell.transactions().execute(jdbc -> {
                harness.poller().poll(cell, 0);
                return null;
            });
        }
        assertThat(created).hasValue(1);
        assertThat(count("SELECT count(*) FROM scheduler.fire_test_rows WHERE schedule_id = :id", id))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM scheduler.schedules WHERE schedule_id = :id", id))
                .isZero();
    }

    @Test
    void materializerFailureRollsBackTheScheduleAndItsWrites() {
        UUID id = seed(1, NOW.plusSeconds(2), 1);
        try (Harness harness = harness((currentCell, schedule, fires) -> {
            currentCell
                    .jdbc()
                    .sql("INSERT INTO scheduler.fire_test_rows VALUES (:id, :at)")
                    .param("id", schedule.id())
                    .param("at", Timestamp.from(fires.getFirst().at()))
                    .update();
            throw new IllegalStateException("injected failure");
        })) {
            assertThatThrownBy(() -> cell.transactions().execute(jdbc -> {
                        harness.poller().poll(cell, 0);
                        return null;
                    }))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(count("SELECT count(*) FROM scheduler.fire_test_rows WHERE schedule_id = :id", id))
                .isZero();
        assertThat(count("SELECT count(*) FROM scheduler.schedules WHERE schedule_id = :id", id))
                .isEqualTo(1);
    }

    @Test
    void heldSchedulesAndOldOutboxRowsStopClaims() {
        UUID held = seed(3, NOW.plusSeconds(2), 1);
        UUID active = seed(1, NOW.plusSeconds(2), 1);
        UUID outboxId = new KairosIdFactory(MutableClock.at(NOW), new SecureRandom()).newId();
        database.jdbc()
                .sql("INSERT INTO scheduler.outbox VALUES (:id, :now, :old)")
                .param("id", outboxId)
                .param("now", Timestamp.from(NOW.minusSeconds(1)))
                .param("old", Timestamp.from(NOW.minusSeconds(20)))
                .update();
        AtomicInteger calls = new AtomicInteger();
        try (Harness harness = harness((currentCell, schedule, fires) -> {
            if (schedule.id().equals(active)) calls.incrementAndGet();
            return fires.size();
        })) {
            cell.transactions().execute(jdbc -> {
                harness.poller().poll(cell, 0);
                return null;
            });
            assertThat(calls).hasValue(0);
            database.jdbc()
                    .sql("DELETE FROM scheduler.outbox WHERE outbox_id = :id")
                    .param("id", outboxId)
                    .update();
            cell.transactions().execute(jdbc -> {
                harness.poller().poll(cell, 0);
                return null;
            });
        }
        assertThat(calls).hasValue(1);
        assertThat(count("SELECT count(*) FROM scheduler.schedules WHERE schedule_id = :id", held))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM scheduler.schedules WHERE schedule_id = :id", active))
                .isZero();
    }

    @Test
    void secondWatcherSkipsAClaimHeldByTheFirst() throws Exception {
        UUID id = seed(1, NOW.plusSeconds(2), 1);
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> cell.transactions().execute(jdbc -> {
                List<FireSchedule> rows = new FireScheduleStore().claim(cell, 0, NOW.plusSeconds(5));
                assertThat(rows).extracting(FireSchedule::id).contains(id);
                claimed.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("Timed out waiting to release claim");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return null;
            }));
            assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                List<FireSchedule> skipped =
                        cell.transactions().execute(jdbc -> new FireScheduleStore().claim(cell, 0, NOW.plusSeconds(5)));
                assertThat(skipped).extracting(FireSchedule::id).doesNotContain(id);
            } finally {
                release.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void forcedHandoverWaitsForTheFiringTransactionAndDoesNotDuplicateAnOccurrence() throws Exception {
        UUID id = seed(1, NOW.plusSeconds(2), 1);
        database.jdbc().sql("""
                INSERT INTO scheduler.fire_test_leases VALUES (0, 'watcher-one', 1)
                ON CONFLICT (shard_id) DO UPDATE SET owner_node = 'watcher-one', generation = 1
                """).update();
        CountDownLatch firing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch handedOver = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        FireMaterializer materializer = (currentCell, schedule, fires) -> {
            for (var fire : fires) {
                created.addAndGet(currentCell
                        .jdbc()
                        .sql("INSERT INTO scheduler.fire_test_rows VALUES (:id, :at) ON CONFLICT DO NOTHING")
                        .param("id", schedule.id())
                        .param("at", Timestamp.from(fire.at()))
                        .update());
            }
            firing.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Handover timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return fires.size();
        };
        try (Harness first = harness(materializer);
                Harness second = harness(materializer);
                var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var firstRun = workers.submit(() -> cell.transactions().execute(jdbc -> {
                assertThat(jdbc.sql("""
                        SELECT generation FROM scheduler.fire_test_leases
                        WHERE shard_id = 0 AND owner_node = 'watcher-one' AND generation = 1
                        FOR SHARE
                        """).query(Long.class).single()).isEqualTo(1);
                first.poller().poll(cell, 0);
                return null;
            }));
            assertThat(firing.await(10, TimeUnit.SECONDS)).isTrue();
            var takeover = workers.submit(() -> {
                database.jdbc().sql("""
                        UPDATE scheduler.fire_test_leases
                        SET owner_node = 'watcher-two', generation = 2
                        WHERE shard_id = 0 AND generation = 1
                        """).update();
                handedOver.countDown();
                cell.transactions().execute(jdbc -> {
                    assertThat(jdbc.sql("""
                            SELECT generation FROM scheduler.fire_test_leases
                            WHERE shard_id = 0 AND owner_node = 'watcher-two' AND generation = 2
                            FOR SHARE
                            """).query(Long.class).single()).isEqualTo(2);
                    second.poller().poll(cell, 0);
                    return null;
                });
                return null;
            });
            try {
                assertThat(handedOver.await(150, TimeUnit.MILLISECONDS)).isFalse();
            } finally {
                release.countDown();
            }
            firstRun.get(10, TimeUnit.SECONDS);
            takeover.get(10, TimeUnit.SECONDS);
        }
        assertThat(created).hasValue(1);
        assertThat(count("SELECT count(*) FROM scheduler.fire_test_rows WHERE schedule_id = :id", id))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM scheduler.schedules WHERE schedule_id = :id", id))
                .isZero();
    }

    @Test
    void tenThousandCronSchedulesDueTogetherEachCreateOneOccurrence() {
        UUID team = new KairosIdFactory(MutableClock.at(NOW), new SecureRandom())
                .newTeamId()
                .value();
        database.jdbc()
                .sql("""
                WITH created AS (
                  INSERT INTO scheduler.jobs (job_id, status)
                  SELECT gen_random_uuid(), 'ACTIVE' FROM generate_series(1, 10000)
                  RETURNING job_id
                )
                INSERT INTO scheduler.schedules (
                  schedule_id, team_id, target_type, shard_id, status, next_fire_at, kind,
                  cron_expr, timezone, spread_ms, dst_policy, misfire_policy, max_catchup,
                  definition_version, priority)
                SELECT job_id, :team, 1, 0, 1, :due, 2, '* * * * *', 'UTC', 0, 1, 2, 100, 1, 5
                FROM created
                """)
                .param("team", team)
                .param("due", Timestamp.from(NOW))
                .update();
        AtomicInteger attempts = new AtomicInteger();
        try (Harness harness = harness((currentCell, schedule, fires) -> {
            for (var fire : fires) {
                if (schedule.teamId().equals(team)) attempts.incrementAndGet();
                currentCell
                        .jdbc()
                        .sql("INSERT INTO scheduler.fire_test_rows VALUES (:id, :at) ON CONFLICT DO NOTHING")
                        .param("id", schedule.id())
                        .param("at", Timestamp.from(fire.at()))
                        .update();
            }
            return fires.size();
        })) {
            for (int cycle = 0; cycle < 41; cycle++) {
                cell.transactions().execute(jdbc -> {
                    harness.poller().poll(cell, 0);
                    return null;
                });
            }
        }
        long inserted =
                database.jdbc().sql("""
                SELECT count(*) FROM scheduler.fire_test_rows r
                JOIN scheduler.schedules s ON s.schedule_id = r.schedule_id
                WHERE s.team_id = :team
                """).param("team", team).query(Long.class).single();
        assertThat(inserted).isEqualTo(10_000);
        assertThat(attempts).hasValue(10_000);
        long advanced = database.jdbc()
                .sql("""
                SELECT count(*) FROM scheduler.schedules
                WHERE team_id = :team AND next_fire_at = :next
                """)
                .param("team", team)
                .param("next", Timestamp.from(NOW.plusSeconds(60)))
                .query(Long.class)
                .single();
        assertThat(advanced).isEqualTo(10_000);
    }

    private static UUID seed(int status, Instant due, int kind) {
        KairosIdFactory ids = new KairosIdFactory(MutableClock.at(NOW), new SecureRandom());
        UUID id = ids.newJobId(Shard.of(0)).value();
        UUID team = ids.newTeamId().value();
        database.jdbc()
                .sql("INSERT INTO scheduler.jobs (job_id, status) VALUES (:id, 'ACTIVE')")
                .param("id", id)
                .update();
        database.jdbc()
                .sql("""
                INSERT INTO scheduler.schedules (
                  schedule_id, team_id, target_type, shard_id, status, next_fire_at, kind,
                  timezone, spread_ms, dst_policy, misfire_policy, max_catchup, definition_version, priority)
                VALUES (:id, :team, 1, 0, :status, :due, :kind, 'UTC', 0, 1, 2, 100, 1, 5)
                """)
                .param("id", id)
                .param("team", team)
                .param("status", status)
                .param("due", Timestamp.from(due))
                .param("kind", kind)
                .update();
        return id;
    }

    private static long count(String sql, UUID id) {
        return database.jdbc().sql(sql).param("id", id).query(Long.class).single();
    }

    private static Harness harness(FireMaterializer materializer) {
        MutableClock clock = MutableClock.at(NOW);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CellRouter router =
                new CellRouter(() -> List.of(cell.range()), Map.of("cell-0", database), Duration.ofMinutes(1), clock);
        ShardedPoller scheduler =
                new ShardedPoller(router, new OwnedShards(), "test-fire", clock, Duration.ZERO, meters);
        FirePoller poller = new FirePoller(
                scheduler,
                new FireScheduleStore(),
                materializer,
                clock,
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                meters);
        return new Harness(scheduler, poller);
    }

    private record Harness(ShardedPoller scheduler, FirePoller poller) implements AutoCloseable {
        @Override
        public void close() {
            poller.close();
            scheduler.close();
        }
    }
}
