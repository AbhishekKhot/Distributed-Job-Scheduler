package dev.kairos.watcher.lease;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.persistence.cell.CellRange;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.cell.CellStatus;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.watcher.node.NodeIdentity;
import dev.kairos.watcher.node.NodeRegistry;
import dev.kairos.watcher.support.ShardedPoller;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Real cell ownership, fair sharing, fencing and poller transaction checks against PostgreSQL. */
@KairosIntegrationTest(kafka = false, redis = false)
class ShardLeaseManagerIntegrationTest {
    private static KairosPostgres postgres;
    private static Database control;
    private static Map<String, Database> cellDatabases;

    @BeforeAll
    static void databases(KairosPostgres instance) {
        postgres = instance;
        control = database("control", postgres.controlOwnerDataSource());
        Database cell0 = database("cell-0", postgres.cellOwnerDataSource(0));
        Database cell1 = database("cell-1", postgres.cellOwnerDataSource(1));
        cellDatabases = Map.of(cell0.name(), cell0, cell1.name(), cell1);
        createTables(control);
        createTables(cell0);
        createTables(cell1);
    }

    @Test
    void twoWatchersShareShardsAndTakeoverAdvancesGeneration() {
        CellRouter router = router();
        ManagerHandle first = manager("watcher-a", router, new SimpleMeterRegistry());
        ManagerHandle second = manager("watcher-b", router, new SimpleMeterRegistry());
        try {
            first.manager().reconcile();
            assertThat(first.owned().count(0)).isEqualTo(128);
            assertThat(first.owned().count(1)).isEqualTo(128);

            second.manager().reconcile();
            first.manager().reconcile();
            second.manager().reconcile();
            assertThat(first.owned().count(0)).isBetween(64, 66);
            assertThat(first.owned().count(1)).isBetween(64, 66);
            assertThat(second.owned().count(0)).isBetween(62, 66);
            assertThat(second.owned().count(1)).isBetween(62, 66);
            assertThat(first.owned().count(0) + second.owned().count(0)).isEqualTo(128);
            assertThat(first.owned().count(1) + second.owned().count(1)).isEqualTo(128);

            int shard = controlForCell(0)
                    .sql(
                            "SELECT shard_id FROM scheduler.shard_leases WHERE owner_node = :node ORDER BY shard_id LIMIT 1")
                    .param("node", second.identity().nodeId())
                    .query(Integer.class)
                    .single();
            long generationBefore = controlForCell(0)
                    .sql("SELECT generation FROM scheduler.shard_leases WHERE shard_id = :shard")
                    .param("shard", shard)
                    .query(Long.class)
                    .single();
            second.manager().close();
            first.manager().reconcile();
            assertThat(first.owned().count(0)).isEqualTo(128);
            assertThat(first.owned().count(1)).isEqualTo(128);
            assertThat(controlForCell(0)
                            .sql("SELECT owner_node FROM scheduler.shard_leases WHERE shard_id = :shard")
                            .param("shard", shard)
                            .query(String.class)
                            .single())
                    .isEqualTo(first.identity().nodeId());
            long generationAfter = controlForCell(0)
                    .sql("SELECT generation FROM scheduler.shard_leases WHERE shard_id = :shard")
                    .param("shard", shard)
                    .query(Long.class)
                    .single();
            assertThat(generationAfter).isGreaterThan(generationBefore);
        } finally {
            first.manager().close();
            second.manager().close();
        }
    }

    @Test
    void pollerCallbackRunsUnderLeaseRowLockAndStopsAfterClose() throws Exception {
        CellRouter router = router();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ManagerHandle handle = manager("poller", router, meters);
        ShardLeaseManager manager = handle.manager();
        manager.reconcile();
        CountDownLatch processed = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean transactional = new java.util.concurrent.atomic.AtomicBoolean();
        ShardedPoller poller = new ShardedPoller(
                router, handle.owned(), handle.identity().nodeId(), Clock.systemUTC(), Duration.ZERO, meters);
        ShardedPoller.Registration registration = poller.register("test", Duration.ofSeconds(1), 8, (cell, shard) -> {
            transactional.set(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive());
            calls.incrementAndGet();
            processed.countDown();
        });
        try {
            assertThat(processed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(calls.get()).isGreaterThan(0);
            assertThat(transactional.get()).isTrue();
        } finally {
            registration.close();
            poller.close();
            manager.close();
        }
    }

    @Test
    void oldGenerationCannotRenewAfterAnotherOwnerTakesOver() {
        ManagerHandle paused = manager("paused", router(), new SimpleMeterRegistry());
        int[] takenOverShard = {-1};
        try {
            paused.manager().reconcile();
            OwnedShards.Lease lease = paused.owned().inCell(0).getFirst();
            takenOverShard[0] = lease.shard();
            JdbcClient cell = controlForCell(0);
            cell.sql("""
                            UPDATE scheduler.shard_leases
                               SET owner_node = 'replacement-test', generation = generation + 1,
                                   lease_until = clock_timestamp() + interval '15 seconds'
                             WHERE shard_id = :shard AND owner_node = :node AND generation = :generation
                            """)
                    .param("shard", lease.shard())
                    .param("node", paused.identity().nodeId())
                    .param("generation", lease.generation())
                    .update();
            OffsetDateTime before = cell.sql("SELECT lease_until FROM scheduler.shard_leases WHERE shard_id = :shard")
                    .param("shard", lease.shard())
                    .query(OffsetDateTime.class)
                    .single();

            paused.manager().reconcile();

            OffsetDateTime after = cell.sql("SELECT lease_until FROM scheduler.shard_leases WHERE shard_id = :shard")
                    .param("shard", lease.shard())
                    .query(OffsetDateTime.class)
                    .single();
            assertThat(paused.owned().inCell(0)).noneMatch(item -> item.shard() == lease.shard());
            assertThat(after).isEqualTo(before);
            assertThat(cell.sql("SELECT generation FROM scheduler.shard_leases WHERE shard_id = :shard")
                            .param("shard", lease.shard())
                            .query(Long.class)
                            .single())
                    .isEqualTo(lease.generation() + 1);
        } finally {
            if (takenOverShard[0] >= 0) {
                controlForCell(0)
                        .sql(
                                "UPDATE scheduler.shard_leases SET owner_node = NULL, lease_until = NULL, generation = generation + 1 WHERE shard_id = :shard AND owner_node = 'replacement-test'")
                        .param("shard", takenOverShard[0])
                        .update();
            }
            paused.manager().close();
        }
    }

    private static ManagerHandle manager(String pod, CellRouter router, SimpleMeterRegistry meters) {
        NodeIdentity identity = identity(pod);
        NodeRegistry registry = new NodeRegistry(control.jdbc(), identity, Duration.ofMinutes(1));
        OwnedShards owned = new OwnedShards();
        ShardLeaseManager manager = new ShardLeaseManager(
                router, registry, owned, Duration.ofSeconds(15), Duration.ofSeconds(3), 2, meters);
        return new ManagerHandle(manager, identity, owned);
    }

    private static NodeIdentity identity(String pod) {
        return NodeIdentity.watcher(pod, Instant.now(), "test");
    }

    private static CellRouter router() {
        return new CellRouter(
                () -> List.of(
                        new CellRange(0, 0, 127, "cell-0", CellStatus.ACTIVE),
                        new CellRange(1, 128, 255, "cell-1", CellStatus.ACTIVE)),
                cellDatabases,
                Duration.ofMinutes(1),
                Clock.systemUTC());
    }

    private static Database database(String name, DataSource source) {
        return Database.of(name, source, DatabaseOptions.defaults(), new SimpleMeterRegistry());
    }

    private static void createTables(Database database) {
        JdbcClient jdbc = database.jdbc();
        jdbc.sql("CREATE SCHEMA IF NOT EXISTS scheduler").update();
        if (database.name().equals("control")) {
            jdbc.sql("""
                    CREATE TABLE IF NOT EXISTS scheduler.scheduler_nodes (
                        node_id text PRIMARY KEY, roles text NOT NULL, version text NOT NULL,
                        started_at timestamptz NOT NULL, heartbeat_at timestamptz NOT NULL)
                    """).update();
        } else {
            jdbc.sql("""
                    CREATE TABLE IF NOT EXISTS scheduler.shard_leases (
                        shard_id smallint PRIMARY KEY, owner_node text,
                        generation bigint NOT NULL DEFAULT 0, lease_until timestamptz,
                        CHECK (shard_id BETWEEN 0 AND 255), CHECK (generation >= 0))
                    """).update();
        }
    }

    private static JdbcClient controlForCell(int cell) {
        return cellDatabases.get(Database.cellName(cell)).jdbc();
    }

    private record ManagerHandle(ShardLeaseManager manager, NodeIdentity identity, OwnedShards owned) {}
}
