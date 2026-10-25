package dev.kairos.maintenance.partitions;

import static dev.kairos.maintenance.partitions.PartitionFixtures.TODAY;
import static dev.kairos.maintenance.partitions.PartitionFixtures.count;
import static dev.kairos.maintenance.partitions.PartitionFixtures.exists;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.maintenance.partitions.application.PartitionDay;
import dev.kairos.maintenance.partitions.application.PartitionManager;
import dev.kairos.maintenance.partitions.application.PartitionStore;
import dev.kairos.maintenance.partitions.application.PartitionTable;
import dev.kairos.maintenance.partitions.infrastructure.JdbcPartitionStore;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@KairosIntegrationTest(kafka = false, redis = false)
class PartitionManagerIntegrationTest {
    private PartitionFixtures f;

    @BeforeEach
    void setup(KairosPostgres postgres) {
        f = new PartitionFixtures(postgres);
        f.reset();
    }

    @Test
    void createsTodayAndThreeDaysAheadInControlAndEveryCellAndIsIdempotent() {
        var result = f.manager().run();
        assertThat(result).hasSize(3);
        assertThat(result.stream().mapToInt(PartitionStore.Result::created).sum())
                .isEqualTo(20);
        for (var db : List.of(f.control, f.cell0, f.cell1))
            for (var table : db == f.control
                    ? List.of(PartitionTable.AUDIT_LOG)
                    : List.of(PartitionTable.RUN_HISTORY, PartitionTable.TASK_OUTPUTS))
                for (int offset = 0; offset <= 3; offset++)
                    assertThat(exists(db, new PartitionDay(table, TODAY.plusDays(offset)).name()))
                            .isTrue();
        assertThat(f.manager().run()).allSatisfy(r -> {
            assertThat(r.created()).isZero();
            assertThat(r.dropped()).isZero();
            assertThat(r.skipped()).isFalse();
        });
        f.clock.advance(Duration.ofDays(1));
        assertThat(f.manager().run().stream()
                        .mapToInt(PartitionStore.Result::created)
                        .sum())
                .isEqualTo(5);
    }

    @Test
    void dropsOnlyCompleteExpiredDaysAndRetainsExactSevenAndNinetyDayBoundaries() {
        for (var db : List.of(f.control, f.cell0, f.cell1))
            for (var table : db == f.control
                    ? List.of(PartitionTable.AUDIT_LOG)
                    : List.of(PartitionTable.RUN_HISTORY, PartitionTable.TASK_OUTPUTS)) {
                var expired = TODAY.minusDays(table.retentionDays() + 1L);
                var boundary = TODAY.minusDays(table.retentionDays());
                f.daily(db, table, expired);
                f.daily(db, table, boundary);
                f.insert(db, table, expired);
                f.insert(db, table, boundary);
            }
        var result = f.manager().run();
        assertThat(result.stream().mapToInt(PartitionStore.Result::dropped).sum())
                .isEqualTo(5);
        for (var db : List.of(f.control, f.cell0, f.cell1))
            for (var table : db == f.control
                    ? List.of(PartitionTable.AUDIT_LOG)
                    : List.of(PartitionTable.RUN_HISTORY, PartitionTable.TASK_OUTPUTS)) {
                assertThat(exists(db, new PartitionDay(table, TODAY.minusDays(table.retentionDays() + 1L)).name()))
                        .isFalse();
                assertThat(exists(db, new PartitionDay(table, TODAY.minusDays(table.retentionDays())).name()))
                        .isTrue();
                assertThat(count(db, table.table())).isEqualTo(1);
                assertThat(exists(db, table.defaultName())).isTrue();
            }
    }

    @Test
    void movesOverlappingFallbackRowsAtomicallyAndPreservesFutureOutOfHorizonRows() {
        for (var db : List.of(f.control, f.cell0, f.cell1))
            for (var table : db == f.control
                    ? List.of(PartitionTable.AUDIT_LOG)
                    : List.of(PartitionTable.RUN_HISTORY, PartitionTable.TASK_OUTPUTS)) {
                f.insert(db, table, TODAY.plusDays(1));
                f.insert(db, table, TODAY.plusDays(2));
                f.insert(db, table, TODAY.plusDays(6));
            }
        var result = f.manager().run();
        assertThat(result.stream().mapToLong(PartitionStore.Result::moved).sum())
                .isEqualTo(10);
        for (var db : List.of(f.control, f.cell0, f.cell1))
            for (var table : db == f.control
                    ? List.of(PartitionTable.AUDIT_LOG)
                    : List.of(PartitionTable.RUN_HISTORY, PartitionTable.TASK_OUTPUTS)) {
                assertThat(count(db, table.table())).isEqualTo(3);
                assertThat(count(db, table.defaultName())).isEqualTo(1);
                assertThat(count(db, new PartitionDay(table, TODAY.plusDays(1)).name()))
                        .isEqualTo(1);
                assertThat(count(db, new PartitionDay(table, TODAY.plusDays(2)).name()))
                        .isEqualTo(1);
            }
    }

    @Test
    void fallbackPurgeUsesBoundedBatchesAndKeepsBoundaryData() {
        for (int i = 0; i < 5; i++) f.insert(f.cell0, PartitionTable.RUN_HISTORY, TODAY.minusDays(8));
        f.insert(f.cell0, PartitionTable.RUN_HISTORY, TODAY.minusDays(7));
        var result = f.store(f.cell0, PartitionTable.RUN_HISTORY).maintain(TODAY);
        assertThat(result.purged()).isEqualTo(5);
        assertThat(count(f.cell0, "run_history_default")).isEqualTo(1);
    }

    @Test
    void defaultCopyFailureRollsBackDeletedRowsAndNewChildAndRetryRecovers() {
        f.insert(f.cell0, PartitionTable.RUN_HISTORY, TODAY.plusDays(1));
        f.cell0
                .jdbc()
                .sql(
                        "ALTER TABLE scheduler.run_history ADD CONSTRAINT test_copy_failure CHECK(latency_ms<10) NOT VALID")
                .update();
        try {
            assertThatThrownBy(
                            () -> f.store(f.cell0, PartitionTable.RUN_HISTORY).maintain(TODAY))
                    .isInstanceOf(RuntimeException.class);
            assertThat(count(f.cell0, "run_history_default")).isEqualTo(1);
            assertThat(exists(f.cell0, new PartitionDay(PartitionTable.RUN_HISTORY, TODAY.plusDays(1)).name()))
                    .isFalse();
        } finally {
            f.cell0
                    .jdbc()
                    .sql("ALTER TABLE scheduler.run_history DROP CONSTRAINT test_copy_failure")
                    .update();
        }
        assertThat(f.store(f.cell0, PartitionTable.RUN_HISTORY).maintain(TODAY).moved())
                .isEqualTo(1);
        assertThat(count(f.cell0, "run_history_default")).isZero();
    }

    @Test
    void anotherSessionHoldingTheAdvisoryLockSkipsAndReleasesOnClose() throws Exception {
        try (var connection = f.control.dataSource().getConnection();
                var statement = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
            statement.setLong(1, JdbcPartitionStore.LOCK_KEY);
            statement.execute();
            try {
                var skipped = f.store(f.control, PartitionTable.AUDIT_LOG).maintain(TODAY);
                assertThat(skipped.skipped()).isTrue();
                assertThat(skipped.created()).isZero();
            } finally {
                try (var unlock = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    unlock.setLong(1, JdbcPartitionStore.LOCK_KEY);
                    unlock.execute();
                }
            }
        }
        assertThat(f.store(f.control, PartitionTable.AUDIT_LOG).maintain(TODAY).created())
                .isEqualTo(4);
    }

    @Test
    void simultaneousManagersCreateEachPartitionOnceWithoutLosingRows() throws Exception {
        f.insert(f.cell1, PartitionTable.TASK_OUTPUTS, TODAY.plusDays(1));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                return f.manager().run();
            });
            var second = executor.submit(() -> {
                start.await();
                return f.manager().run();
            });
            start.countDown();
            var left = first.get(30, TimeUnit.SECONDS);
            var right = second.get(30, TimeUnit.SECONDS);
            int created = left.stream().mapToInt(PartitionStore.Result::created).sum()
                    + right.stream().mapToInt(PartitionStore.Result::created).sum();
            assertThat(created).isEqualTo(20);
        }
        assertThat(count(f.cell1, "task_outputs")).isEqualTo(1);
        assertThat(count(f.cell1, "task_outputs_default")).isZero();
        assertThat(f.manager().run()).allSatisfy(r -> assertThat(r.created()).isZero());
    }

    @Test
    void schemaNameCollisionFailsClosedAndAdvisoryLockIsReleased() throws Exception {
        String child = new PartitionDay(PartitionTable.AUDIT_LOG, TODAY).name();
        f.control
                .jdbc()
                .sql("CREATE TABLE scheduler." + child + " (sentinel integer)")
                .update();
        try {
            assertThatThrownBy(
                            () -> f.store(f.control, PartitionTable.AUDIT_LOG).maintain(TODAY))
                    .isInstanceOf(IllegalStateException.class);
            try (var connection = f.control.dataSource().getConnection();
                    var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, JdbcPartitionStore.LOCK_KEY);
                try (var result = statement.executeQuery()) {
                    result.next();
                    assertThat(result.getBoolean(1)).isTrue();
                }
                try (var unlock = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    unlock.setLong(1, JdbcPartitionStore.LOCK_KEY);
                    unlock.execute();
                }
            }
        } finally {
            f.control.jdbc().sql("DROP TABLE scheduler." + child).update();
        }
    }

    @Test
    void oldLookingPartitionWithWrongBoundsIsNeverDropped() {
        String name = new PartitionDay(PartitionTable.AUDIT_LOG, TODAY.minusDays(91)).name();
        f.control
                .jdbc()
                .sql(
                        "CREATE TABLE scheduler." + name
                                + " PARTITION OF scheduler.audit_log FOR VALUES FROM ('2026-12-01T00:00:00Z') TO ('2026-12-02T00:00:00Z')")
                .update();
        assertThatThrownBy(() -> f.store(f.control, PartitionTable.AUDIT_LOG).maintain(TODAY))
                .isInstanceOf(IllegalStateException.class);
        assertThat(exists(f.control, name)).isTrue();
    }

    @Test
    void unmanagedCustomNamedPartitionsAreRetained() {
        f.control
                .jdbc()
                .sql(
                        "CREATE TABLE scheduler.audit_log_custom PARTITION OF scheduler.audit_log FOR VALUES FROM ('2020-01-01T00:00:00Z') TO ('2020-01-02T00:00:00Z')")
                .update();
        f.store(f.control, PartitionTable.AUDIT_LOG).maintain(TODAY);
        assertThat(exists(f.control, "audit_log_custom")).isTrue();
    }

    @Test
    void lockWaitTimesOutWithoutMovingFallbackRowsAndCanBeRetried() throws Exception {
        f.insert(f.cell0, PartitionTable.RUN_HISTORY, TODAY.plusDays(1));
        try (var connection = f.cell0.dataSource().getConnection();
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("LOCK TABLE ONLY scheduler.run_history IN SHARE MODE");
            try {
                assertThatThrownBy(() ->
                                f.store(f.cell0, PartitionTable.RUN_HISTORY).maintain(TODAY))
                        .isInstanceOf(RuntimeException.class);
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
        assertThat(count(f.cell0, "run_history_default")).isEqualTo(1);
        assertThat(f.store(f.cell0, PartitionTable.RUN_HISTORY).maintain(TODAY).moved())
                .isEqualTo(1);
    }

    @Test
    void ordinaryApplicationRoleCannotRunPartitionDdlAndNewChildrenKeepDmlGrants() {
        var app = dev.kairos.persistence.jdbc.Database.of(
                "control",
                f.postgres.controlDataSource(),
                dev.kairos.persistence.jdbc.DatabaseOptions.defaults(),
                f.meters);
        assertThatThrownBy(() -> f.store(app, PartitionTable.AUDIT_LOG).maintain(TODAY))
                .isInstanceOf(IllegalStateException.class);
        f.manager().run();
        JdbcClient application = JdbcClient.create(f.postgres.controlDataSource());
        application
                .sql("INSERT INTO scheduler.audit_log(audit_id,at,actor,action) VALUES (:id,:at,'system:test','TEST')")
                .param("id", java.util.UUID.randomUUID())
                .param("at", TODAY.plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC))
                .update();
        assertThat(application
                        .sql("SELECT count(*) FROM scheduler.audit_log")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void retentionNeverPurgesUnresolvedDeadLetters() {
        var id = dev.kairos.testsupport.TestIds.dlqId(12).value();
        var job = dev.kairos.testsupport.TestIds.jobId(12).value();
        f.cell0
                .jdbc()
                .sql("""
                INSERT INTO scheduler.dead_letters(dlq_id,team_id,run_id,shard_id,scheduled_at,target_type,definition_id,
                  definition_version,target_id,endpoint_id,endpoint_version,attempt,infra_attempt,priority,reason,created_at,fence)
                VALUES (:id,:team,:run,12,:at,1,:job,1,:job,:endpoint,1,6,0,5,7,:at,8)
                """)
                .param("id", id)
                .param("team", dev.kairos.testsupport.TestIds.teamId().value())
                .param("run", dev.kairos.testsupport.TestIds.runId(12).value())
                .param("job", job)
                .param("endpoint", dev.kairos.testsupport.TestIds.endpointId().value())
                .param("at", TODAY.minusYears(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC))
                .update();
        f.manager().run();
        assertThat(f.cell0
                        .jdbc()
                        .sql("SELECT status=1 AND resolved_at IS NULL FROM scheduler.dead_letters WHERE dlq_id=:id")
                        .param("id", id)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void fallbackPurgeSkipsLockedRowsAndReplaysAfterTheWriterFinishes() throws Exception {
        f.manager().run();
        var id = f.insert(f.cell0, PartitionTable.RUN_HISTORY, TODAY.minusDays(8));
        try (var connection = f.cell0.dataSource().getConnection();
                var statement = connection.prepareStatement(
                        "SELECT run_id FROM scheduler.run_history_default WHERE run_id=? FOR UPDATE")) {
            connection.setAutoCommit(false);
            statement.setObject(1, id);
            statement.executeQuery().close();
            try {
                assertThat(f.store(f.cell0, PartitionTable.RUN_HISTORY)
                                .maintain(TODAY)
                                .purged())
                        .isZero();
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
        assertThat(f.store(f.cell0, PartitionTable.RUN_HISTORY).maintain(TODAY).purged())
                .isEqualTo(1);
    }

    @Test
    void partialDatabaseProgressCanBeReplayedAfterFailure() {
        var partial = new PartitionManager(f.clock, List.of(f.store(f.control, PartitionTable.AUDIT_LOG), today -> {
            throw new IllegalStateException("cell unavailable");
        }));
        assertThatThrownBy(partial::run).isInstanceOf(IllegalStateException.class);
        var replay = f.manager().run();
        assertThat(replay.getFirst().created()).isZero();
        assertThat(replay.stream().mapToInt(PartitionStore.Result::created).sum())
                .isEqualTo(16);
    }
}
