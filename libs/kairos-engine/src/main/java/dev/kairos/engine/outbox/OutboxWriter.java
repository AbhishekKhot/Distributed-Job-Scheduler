package dev.kairos.engine.outbox;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.shard.Shard;
import dev.kairos.persistence.cell.Cell;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Writes outbox rows into the caller's open cell transaction (the transactional outbox, HLD section 6).
 *
 * <p>Every message caused by a state change is written in the same transaction as the change, so both commit or
 * neither does; the outbox relay publishes committed rows and deletes them after Kafka acknowledges. The writer must
 * therefore be called inside a {@link dev.kairos.persistence.tx.CellTransactions} callback on the cell of the rows'
 * shards, and it throws {@link IllegalStateException} otherwise, before any row is written. All rows of one call go to
 * that one cell, written as a JDBC batch.
 *
 * <p>Row ids are time-ordered Kairos ids carrying the row's shard, and {@code created_at} comes from the injected
 * clock.
 *
 * <p>Thread-safe.
 */
public final class OutboxWriter {

    static final String INSERT = """
            INSERT INTO scheduler.outbox (shard_id, outbox_id, topic, msg_key, payload, trace_parent, run_id,
                available_at, created_at)
            VALUES (:shardId, :outboxId, :topic, :msgKey, :payload, :traceParent, :runId, :availableAt, :createdAt)
            """;

    private final CellTransactionGuard guard;
    private final KairosIdFactory ids;
    private final Clock clock;

    /**
     * Creates the writer.
     *
     * @param guard checks that a transaction is open on the rows' cell
     * @param ids creates the row ids
     * @param clock the time source of {@code created_at}
     */
    public OutboxWriter(CellTransactionGuard guard, KairosIdFactory ids, Clock clock) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Writes one row.
     *
     * @param message the row
     * @return the new row's {@code outbox_id}
     * @throws IllegalStateException if no transaction is open on the row's cell
     */
    public UUID write(OutboxMessage message) {
        return write(List.of(message)).getFirst();
    }

    /**
     * Writes rows in one batch.
     *
     * @param messages the rows; all of them on the cell the open transaction belongs to; may be empty
     * @return the new rows' {@code outbox_id}s, in input order
     * @throws IllegalStateException if a row's cell has no open transaction on this thread (for example because rows
     *     of two cells were mixed); nothing is written then
     */
    public List<UUID> write(List<OutboxMessage> messages) {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            return List.of();
        }
        Cell cell = requireOneCell(messages);
        OffsetDateTime createdAt =
                OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        List<MapSqlParameterSource> rows = new ArrayList<>(messages.size());
        List<UUID> outboxIds = new ArrayList<>(messages.size());
        for (OutboxMessage message : messages) {
            UUID outboxId = ids.newId(message.shard());
            outboxIds.add(outboxId);
            rows.add(parameters(message, outboxId, createdAt));
        }
        cell.database().batchUpdate(INSERT, rows, row -> row);
        return List.copyOf(outboxIds);
    }

    private Cell requireOneCell(List<OutboxMessage> messages) {
        // A thread holds at most one transaction, so once every shard's cell has passed the guard they are all the same
        // cell.
        Set<Shard> shards = new HashSet<>();
        for (OutboxMessage message : messages) {
            shards.add(Objects.requireNonNull(message, "message").shard());
        }
        Cell cell = guard.requireTransaction(messages.getFirst().shard());
        for (Shard shard : shards) {
            guard.requireTransaction(shard);
        }
        return cell;
    }

    private static MapSqlParameterSource parameters(OutboxMessage message, UUID outboxId, OffsetDateTime createdAt) {
        // Explicit SQL types, so null trace parents and run ids bind without a driver round trip for metadata.
        return new MapSqlParameterSource()
                .addValue("shardId", (short) message.shard().value(), Types.SMALLINT)
                .addValue("outboxId", outboxId, Types.OTHER)
                .addValue("topic", (short) message.topic().code(), Types.SMALLINT)
                .addValue("msgKey", message.key().toByteArray(), Types.BINARY)
                .addValue("payload", message.payload().toByteArray(), Types.BINARY)
                .addValue("traceParent", message.traceParent(), Types.VARCHAR)
                .addValue("runId", message.runId(), Types.OTHER)
                .addValue("availableAt", utc(message.availableAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("createdAt", createdAt, Types.TIMESTAMP_WITH_TIMEZONE);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
