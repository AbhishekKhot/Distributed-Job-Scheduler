package dev.kairos.engine.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.id.KairosId;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.model.OutboxTopic;
import dev.kairos.domain.shard.Shard;
import dev.kairos.engine.EngineFixtures;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.jdbc.DataSourceRegistry;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.tx.CellTransactions;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.Cancel;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.TestIds;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The outbox writer on the real partitioned {@code outbox} table: columns, transactions and the transaction rule. */
@KairosIntegrationTest(kafka = false, redis = false)
class OutboxWriterIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private static final String TRACE = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final String SELECT_ROW = """
            SELECT shard_id, topic, msg_key, payload, trace_parent, run_id, available_at, created_at
            FROM scheduler.outbox WHERE shard_id = :shardId AND outbox_id = :outboxId
            """;
    private static final String COUNT_RUN_ROWS =
            "SELECT count(*) FROM scheduler.outbox WHERE shard_id = :shardId AND run_id = :runId";

    private final MutableClock clock = MutableClock.at(NOW);
    private DataSourceRegistry registry;
    private CellTransactions cells;
    private ControlTransactions control;
    private OutboxWriter writer;

    @BeforeEach
    void setUp(KairosPostgres postgres) {
        registry = EngineFixtures.registry(postgres);
        CellRouter router = EngineFixtures.router(registry);
        cells = new CellTransactions(router);
        control = new ControlTransactions(registry.control());
        writer = new OutboxWriter(new CellTransactionGuard(router), TestIds.factory(clock), clock);
    }

    @Test
    void writesEveryColumnInTheCallersTransaction() {
        RunId runId = TestIds.runId(140);
        OutboxMessage message = OutboxMessage.dispatch(dispatch(runId, 9), NOW.plusSeconds(3));

        UUID outboxId = cells.execute(runId.shard(), jdbc -> writer.write(message));

        Map<String, Object> row = cell1().jdbc()
                .sql(SELECT_ROW)
                .param("shardId", (short) 140)
                .param("outboxId", outboxId)
                .query()
                .singleRow();
        assertThat(KairosId.shardOf(outboxId)).isEqualTo(Shard.of(140));
        assertThat(row.get("shard_id")).isEqualTo(140);
        assertThat(row.get("topic")).isEqualTo(OutboxTopic.RUN_HIGH.code());
        assertThat(row.get("msg_key")).isEqualTo(message.key().toByteArray());
        assertThat(row.get("payload")).isEqualTo(message.payload().toByteArray());
        assertThat(row.get("trace_parent")).isEqualTo(TRACE);
        assertThat(row.get("run_id")).isEqualTo(runId.value());
        assertThat(instant(row.get("available_at"))).isEqualTo(NOW.plusSeconds(3));
        assertThat(instant(row.get("created_at"))).isEqualTo(NOW);
    }

    @Test
    void writesSeveralRowsOfOneCellInOneBatch() {
        RunId first = TestIds.runId(3);
        RunId second = TestIds.runId(90);
        Cancel cancel = Cancel.newBuilder()
                .setRunId(ProtoIds.toBytes(second.value()))
                .setShard(90)
                .setFence(4)
                .build();

        List<UUID> ids = cells.execute(
                first.shard(),
                jdbc -> writer.write(List.of(
                        OutboxMessage.dispatch(dispatch(first, 2), NOW), OutboxMessage.cancel(cancel, NOW, null))));

        assertThat(ids).hasSize(2).doesNotHaveDuplicates();
        assertThat(countRows(cell0(), first)).isEqualTo(1);
        assertThat(countRows(cell0(), second)).isEqualTo(1);
    }

    @Test
    void rowsRollBackWithTheTransaction() {
        RunId runId = TestIds.runId(10);

        assertThatThrownBy(() -> cells.execute(runId.shard(), jdbc -> {
                    writer.write(OutboxMessage.dispatch(dispatch(runId, 5), NOW));
                    throw new IllegalStateException("the state change failed");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the state change failed");

        assertThat(countRows(cell0(), runId)).isZero();
    }

    @Test
    void refusesToWriteOutsideATransaction() {
        RunId runId = TestIds.runId(10);
        OutboxMessage message = OutboxMessage.dispatch(dispatch(runId, 5), NOW);

        assertThatThrownBy(() -> writer.write(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CellTransactions");

        assertThat(countRows(cell0(), runId)).isZero();
    }

    @Test
    void refusesToWriteInsideATransactionOnAnotherDatabase() {
        RunId runId = TestIds.runId(200);
        RunId otherCell = TestIds.runId(20);

        assertThatThrownBy(() -> control.execute(jdbc -> writer.write(OutboxMessage.dispatch(dispatch(runId, 5), NOW))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cells.execute(
                        otherCell.shard(), jdbc -> writer.write(OutboxMessage.dispatch(dispatch(runId, 5), NOW))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cells.execute(
                        otherCell.shard(),
                        jdbc -> writer.write(List.of(
                                OutboxMessage.dispatch(dispatch(otherCell, 5), NOW),
                                OutboxMessage.dispatch(dispatch(runId, 5), NOW)))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(countRows(cell1(), runId)).isZero();
        assertThat(countRows(cell0(), otherCell)).isZero();
    }

    @Test
    void writingNothingNeedsNoTransaction() {
        assertThat(writer.write(List.of())).isEmpty();
    }

    private Dispatch dispatch(RunId runId, int priority) {
        return Dispatch.newBuilder()
                .setRunId(ProtoIds.toBytes(runId.value()))
                .setShard(runId.shard().value())
                .setFence(1)
                .setTargetId(
                        ProtoIds.toBytes(TestIds.jobId(runId.shard().value()).value()))
                .setPriority(priority)
                .setRequest(EngineFixtures.call().request())
                .setTraceParent(TRACE)
                .setTaskKey("")
                .build();
    }

    private Database cell0() {
        return registry.cell(Database.cellName(0)).orElseThrow();
    }

    private Database cell1() {
        return registry.cell(Database.cellName(1)).orElseThrow();
    }

    private static long countRows(Database cell, RunId runId) {
        return cell.jdbc()
                .sql(COUNT_RUN_ROWS)
                .param("shardId", (short) runId.shard().value())
                .param("runId", runId.value())
                .query(Long.class)
                .single();
    }

    private static Instant instant(Object value) {
        // The driver returns timestamptz as java.sql.Timestamp from getObject without a type.
        return value instanceof OffsetDateTime time ? time.toInstant() : ((Timestamp) value).toInstant();
    }
}
