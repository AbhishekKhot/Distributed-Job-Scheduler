package dev.kairos.watcher.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.messaging.client.KafkaClientFactory;
import dev.kairos.messaging.client.KafkaProperties;
import dev.kairos.messaging.client.KafkaPublisher;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRange;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.cell.CellStatus;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.testsupport.Awaits;
import dev.kairos.testsupport.KairosKafka;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.watcher.lease.OwnedShards;
import dev.kairos.watcher.support.ShardedPoller;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Outbox acknowledgements, retry and rollback against PostgreSQL with a controlled Kafka producer. */
class OutboxRelayIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static PostgreSQLContainer postgres;
    private static Database database;
    private static Cell cell;
    private static CellRouter router;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer(DockerImageName.parse(
                                "postgres:18.6@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336")
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("outbox")
                .withUsername("outbox")
                .withPassword("outbox");
        postgres.start();
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        database = Database.of("cell-0", source, DatabaseOptions.defaults(), new SimpleMeterRegistry());
        database.jdbc().sql("CREATE SCHEMA scheduler").update();
        database.jdbc().sql("""
                CREATE TABLE scheduler.outbox (
                  shard_id smallint NOT NULL, outbox_id uuid NOT NULL, topic smallint NOT NULL,
                  msg_key bytea NOT NULL, payload bytea NOT NULL, trace_parent text, run_id uuid,
                  available_at timestamptz NOT NULL, created_at timestamptz NOT NULL,
                  PRIMARY KEY (shard_id, outbox_id))
                """).update();
        cell = new Cell(new CellRange(0, 0, 255, "cell-0", CellStatus.ACTIVE), database);
        router = new CellRouter(
                () -> List.of(cell.range()), Map.of("cell-0", database), Duration.ofMinutes(1), MutableClock.at(NOW));
    }

    @AfterAll
    static void stopPostgres() {
        postgres.close();
    }

    @BeforeEach
    void clear() {
        database.jdbc().sql("DELETE FROM scheduler.outbox").update();
    }

    @Test
    void rowsAreDeletedOnlyAfterEveryOwnAcknowledgement() {
        UUID first = seed(1, NOW);
        UUID second = seed(2, NOW);
        MockProducer<byte[], byte[]> producer = producer(false);
        try (Harness harness = harness(producer, 0)) {
            CompletableFuture<Void> polling = asyncPoll(harness);
            Awaits.until(() -> producer.history().size() == 2);
            assertThat(count()).isEqualTo(2);
            assertThat(producer.history().getFirst().topic()).isEqualTo("kairos.run.completed");
            assertThat(producer.history().getFirst().key()).containsExactly((byte) 1);
            assertThat(producer.completeNext()).isTrue();
            assertThat(count()).isEqualTo(2);
            assertThat(producer.completeNext()).isTrue();
            polling.join();
            assertThat(count()).isZero();
            assertThat(harness.meters()
                            .get("kairos_outbox_published_total")
                            .tag("topic", "kairos.run.completed")
                            .counter()
                            .count())
                    .isEqualTo(2);
            assertThat(first).isNotEqualTo(second);
        }
    }

    @Test
    void failedRowRemainsAndRetriesAfterBackoffWhileAcknowledgedRowDeletes() {
        UUID failed = seed(1, NOW);
        seed(2, NOW);
        MockProducer<byte[], byte[]> producer = producer(false);
        try (Harness harness = harness(producer, 0)) {
            CompletableFuture<Void> polling = asyncPoll(harness);
            Awaits.until(() -> producer.history().size() == 2);
            assertThat(producer.errorNext(new IllegalStateException("broker unavailable")))
                    .isTrue();
            assertThat(producer.completeNext()).isTrue();
            polling.join();
            assertThat(count()).isEqualTo(1);
            assertThat(availableAt(failed)).isEqualTo(NOW.plusSeconds(1));

            harness.clock().advance(Duration.ofSeconds(1));
            CompletableFuture<Void> retry = asyncPoll(harness);
            Awaits.until(() -> producer.history().size() == 3);
            assertThat(count()).isEqualTo(1);
            assertThat(producer.completeNext()).isTrue();
            retry.join();
            assertThat(count()).isZero();
        }
    }

    @Test
    void rollbackAfterAcknowledgementLeavesTheRowForAtLeastOnceReplay() {
        seed(1, NOW);
        try (Harness harness = harness(producer(true), 0)) {
            assertThatThrownBy(() -> cell.transactions().execute(jdbc -> {
                        harness.relay().poll(cell, 0);
                        throw new IllegalStateException("crash before commit");
                    }))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(count()).isEqualTo(1);
            assertThat(harness.meters().find("kairos_outbox_published_total").counter())
                    .isNull();
        }
    }

    @Test
    void optionalCapLeavesUnsentRowsDue() {
        seed(1, NOW);
        seed(2, NOW);
        try (Harness harness = harness(producer(true), 1)) {
            poll(harness);
            assertThat(count()).isEqualTo(1);
            assertThat(harness.meters()
                            .get("kairos_outbox_backlog_rows")
                            .gauge()
                            .value())
                    .isEqualTo(1);
            harness.clock().advance(Duration.ofSeconds(1));
            poll(harness);
            assertThat(count()).isZero();
        }
    }

    @Test
    void claimUsesOutboxIdOrderAndStopsAtTwoThousandDueRows() {
        database.jdbc().sql("""
                INSERT INTO scheduler.outbox (shard_id, outbox_id, topic, msg_key, payload, available_at, created_at)
                SELECT 0, lpad(to_hex(g), 32, '0')::uuid, 5, decode('01', 'hex'),
                       decode('0a', 'hex'), :now, :now
                  FROM generate_series(1, 2001) g
                """).param("now", Timestamp.from(NOW)).update();
        List<OutboxRow> claimed = cell.transactions().execute(jdbc -> new OutboxStore().claim(cell, 0, NOW, 2_000));
        assertThat(claimed).hasSize(2_000);
        assertThat(claimed.getFirst().id()).isEqualTo(new UUID(0, 1));
        assertThat(claimed.getLast().id()).isEqualTo(new UUID(0, 2_000));
        assertThat(count()).isEqualTo(2_001);
    }

    @Test
    void futureRowsStayOutOfTheClaim() {
        UUID future = seed(1, NOW.plusSeconds(1));
        seed(2, NOW);
        List<OutboxRow> claimed = cell.transactions().execute(jdbc -> new OutboxStore().claim(cell, 0, NOW, 2_000));
        assertThat(claimed).extracting(OutboxRow::id).containsExactly(new UUID(0, 2));
        assertThat(count()).isEqualTo(2);
        assertThat(availableAt(future)).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void repeatedlyFailingOldRowBacksOffToThirtySeconds() {
        UUID id = seed(1, NOW);
        database.jdbc()
                .sql("UPDATE scheduler.outbox SET created_at = :old WHERE outbox_id = :id")
                .param("old", Timestamp.from(NOW.minusSeconds(65)))
                .param("id", id)
                .update();
        MockProducer<byte[], byte[]> producer = producer(false);
        try (Harness harness = harness(producer, 0)) {
            CompletableFuture<Void> polling = asyncPoll(harness);
            Awaits.until(() -> producer.history().size() == 1);
            assertThat(producer.errorNext(new IllegalStateException("broker unavailable")))
                    .isTrue();
            polling.join();
            assertThat(availableAt(id)).isEqualTo(NOW.plusSeconds(30));
        }
    }

    @Test
    void backlogMetricsExposeOldestDueAgeAndRowCount() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        OutboxMetrics metrics = new OutboxMetrics(meters);
        metrics.backlog(cell, new OutboxStore.Backlog(4, NOW.minusSeconds(12)), NOW);
        assertThat(meters.get("kairos_outbox_backlog_rows").gauge().value()).isEqualTo(4);
        assertThat(meters.get("kairos_outbox_oldest_due_seconds").gauge().value())
                .isEqualTo(12);
    }

    @Test
    void committedRowsReachRealKafkaAndDrain() {
        for (int key = 1; key <= 10; key++) seed(key, NOW);
        String bootstrap = KairosKafka.get().bootstrapServers();
        Map<String, Object> consumerConfig = new HashMap<>();
        consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "relay-test-" + UUID.randomUUID());
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerConfig.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        MutableClock clock = MutableClock.at(NOW);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try (KafkaConsumer<byte[], byte[]> consumer =
                        new KafkaConsumer<>(consumerConfig, new ByteArrayDeserializer(), new ByteArrayDeserializer());
                KafkaPublisher publisher =
                        new KafkaClientFactory(KafkaProperties.plaintext(bootstrap), meters).publisher("outbox-test");
                ShardedPoller scheduler =
                        new ShardedPoller(router, new OwnedShards(), "relay-kafka-test", clock, Duration.ZERO, meters);
                OutboxRelay relay = new OutboxRelay(
                        scheduler,
                        new OutboxStore(),
                        publisher,
                        new RelayRateLimiter(0),
                        clock,
                        new OutboxMetrics(meters))) {
            consumer.subscribe(List.of("kairos.run.completed"));
            cell.transactions().execute(jdbc -> {
                relay.poll(cell, 0);
                return null;
            });
            assertThat(count()).isZero();
            Set<Integer> keys = new HashSet<>();
            Awaits.await(Duration.ofSeconds(20)).until(() -> {
                consumer.poll(Duration.ofMillis(100)).forEach(record -> {
                    if (record.key().length == 1) keys.add(Byte.toUnsignedInt(record.key()[0]));
                });
                return keys.containsAll(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
            });
        }
    }

    private static MockProducer<byte[], byte[]> producer(boolean autoComplete) {
        return new MockProducer<>(autoComplete, null, new ByteArraySerializer(), new ByteArraySerializer());
    }

    private static Harness harness(MockProducer<byte[], byte[]> producer, int cap) {
        MutableClock clock = MutableClock.at(NOW);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ShardedPoller scheduler =
                new ShardedPoller(router, new OwnedShards(), "relay-test", clock, Duration.ofMillis(200), meters);
        KafkaPublisher publisher = new KafkaPublisher(producer, () -> {});
        OutboxRelay relay = new OutboxRelay(
                scheduler, new OutboxStore(), publisher, new RelayRateLimiter(cap), clock, new OutboxMetrics(meters));
        return new Harness(clock, scheduler, publisher, relay, meters);
    }

    private static void poll(Harness harness) {
        cell.transactions().execute(jdbc -> {
            harness.relay().poll(cell, 0);
            return null;
        });
    }

    private static CompletableFuture<Void> asyncPoll(Harness harness) {
        return CompletableFuture.runAsync(() -> poll(harness));
    }

    private static UUID seed(int key, Instant availableAt) {
        UUID id = new UUID(0, key);
        database.jdbc()
                .sql("""
                INSERT INTO scheduler.outbox (shard_id, outbox_id, topic, msg_key, payload, available_at, created_at)
                VALUES (0, :id, 5, :key, :payload, :available, :created)
                """)
                .param("id", id)
                .param("key", new byte[] {(byte) key})
                .param("payload", new byte[] {(byte) (key + 10)})
                .param("available", Timestamp.from(availableAt))
                .param("created", Timestamp.from(NOW))
                .update();
        return id;
    }

    private static long count() {
        return database.jdbc()
                .sql("SELECT count(*) FROM scheduler.outbox")
                .query(Long.class)
                .single();
    }

    private static Instant availableAt(UUID id) {
        return database.jdbc()
                .sql("SELECT available_at FROM scheduler.outbox WHERE outbox_id = :id")
                .param("id", id)
                .query((rs, row) -> rs.getTimestamp(1).toInstant())
                .single();
    }

    private record Harness(
            MutableClock clock,
            ShardedPoller scheduler,
            KafkaPublisher publisher,
            OutboxRelay relay,
            SimpleMeterRegistry meters)
            implements AutoCloseable {
        @Override
        public void close() {
            relay.close();
            scheduler.close();
            publisher.close();
        }
    }
}
