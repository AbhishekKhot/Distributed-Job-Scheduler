package dev.kairos.watcher.outbox;

import com.google.protobuf.ByteString;
import dev.kairos.domain.model.OutboxTopic;
import dev.kairos.persistence.cell.Cell;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Claims due rows, deletes acknowledgements and defers publish failures in the cell transaction. */
public final class OutboxStore {
    private static final String CLAIM = """
            SELECT outbox_id, topic, msg_key, payload, trace_parent, created_at
              FROM scheduler.outbox
             WHERE shard_id = :shard AND available_at <= :now
             ORDER BY outbox_id
             LIMIT :batch FOR UPDATE SKIP LOCKED
            """;
    private static final String DELETE = """
            DELETE FROM scheduler.outbox
             WHERE shard_id = :shard AND outbox_id IN (:ids)
            """;
    private static final String DEFER = """
            UPDATE scheduler.outbox SET available_at = :retry
             WHERE shard_id = :shard AND outbox_id IN (:ids)
            """;
    private static final String BACKLOG = """
            SELECT count(*), min(available_at) FILTER (WHERE available_at <= :now)
              FROM scheduler.outbox
            """;

    /** Claims no more than the supplied batch size for one owned shard. */
    public List<OutboxRow> claim(Cell cell, int shard, Instant now, int batchSize) {
        Objects.requireNonNull(cell, "cell");
        if (batchSize < 1 || batchSize > 2_000) throw new IllegalArgumentException("Invalid outbox batch size");
        return cell.jdbc()
                .sql(CLAIM)
                .param("shard", shard)
                .param("now", Timestamp.from(now))
                .param("batch", batchSize)
                .query((rs, row) -> new OutboxRow(
                        rs.getObject("outbox_id", UUID.class),
                        OutboxTopic.fromCode(rs.getInt("topic")),
                        ByteString.copyFrom(rs.getBytes("msg_key")),
                        ByteString.copyFrom(rs.getBytes("payload")),
                        rs.getString("trace_parent"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /** Removes only rows whose producer future reported a Kafka acknowledgement. */
    public void deleteAcknowledged(Cell cell, int shard, List<UUID> ids) {
        if (ids.isEmpty()) return;
        if (ids.size() > 2_000) throw new IllegalArgumentException("Outbox delete batch exceeds 2000");
        int deleted =
                cell.jdbc().sql(DELETE).param("shard", shard).param("ids", ids).update();
        if (deleted != ids.size()) throw new IllegalStateException("Acknowledged outbox row changed before delete");
    }

    /** Pushes failed rows to their next retry window without removing them. */
    public void deferFailed(Cell cell, int shard, List<UUID> ids, Instant retryAt) {
        if (ids.isEmpty()) return;
        int changed = cell.jdbc()
                .sql(DEFER)
                .param("shard", shard)
                .param("ids", ids)
                .param("retry", Timestamp.from(retryAt))
                .update();
        if (changed != ids.size()) throw new IllegalStateException("Failed outbox row changed before deferral");
    }

    /** Returns the cell-wide backlog and age anchor for watcher metrics. */
    public Backlog backlog(Cell cell, Instant now) {
        return cell.jdbc()
                .sql(BACKLOG)
                .param("now", Timestamp.from(now))
                .query((rs, row) -> {
                    Timestamp oldest = rs.getTimestamp(2);
                    return new Backlog(rs.getLong(1), oldest == null ? null : oldest.toInstant());
                })
                .single();
    }

    /** Backlog size and the oldest currently due row, if any. */
    public record Backlog(
            long rows, @org.jspecify.annotations.Nullable Instant oldestDue) {}
}
