package dev.kairos.api.support.idempotency.infrastructure;

import dev.kairos.api.support.idempotency.IdempotencyStore;
import dev.kairos.api.support.idempotency.StoredResponse;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.shard.Shard;
import dev.kairos.persistence.cell.CellRouter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link IdempotencyStore} on {@code api_idempotency}, in the control database or in the cell that owns the resource
 * (LLD section 2.2). Every statement is keyed by the primary key {@code (team_id, idem_key)}; updates and deletes are
 * conditional on the {@code created_at} the caller claimed, so a request that lost its claim to a takeover cannot
 * overwrite the new owner's record.
 *
 * <p>{@code response_body} holds a small envelope: {@code {"contentType", "headers": {"Location", "ETag"}, "body"}},
 * where {@code body} is the response text as a JSON string. Keeping the text as a string makes the replay
 * byte-identical; {@code jsonb} would reorder the keys of a JSON object. Thread-safe.
 */
public final class JdbcIdempotencyStore implements IdempotencyStore {

    private static final String INSERT = """
            INSERT INTO scheduler.api_idempotency (team_id, idem_key, request_hash, status, created_at)
            VALUES (:teamId, :key, :requestHash, 1, :at)
            ON CONFLICT (team_id, idem_key) DO NOTHING
            """;
    private static final String FIND = """
            SELECT request_hash, status, http_status, response_body::text AS response_body, created_at
              FROM scheduler.api_idempotency
             WHERE team_id = :teamId AND idem_key = :key
            """;
    private static final String TAKE_OVER = """
            UPDATE scheduler.api_idempotency
               SET request_hash = :requestHash, status = 1, http_status = NULL, response_body = NULL, created_at = :at
             WHERE team_id = :teamId AND idem_key = :key AND created_at = :seenCreatedAt
            """;
    private static final String COMPLETE = """
            UPDATE scheduler.api_idempotency
               SET status = 2, http_status = :httpStatus, response_body = CAST(:responseBody AS jsonb)
             WHERE team_id = :teamId AND idem_key = :key AND created_at = :claimedAt AND status = 1
            """;
    private static final String RELEASE = """
            DELETE FROM scheduler.api_idempotency
             WHERE team_id = :teamId AND idem_key = :key AND created_at = :claimedAt AND status = 1
            """;

    private final JdbcClient control;
    private final CellRouter cells;
    private final JsonMapper json;

    /**
     * Creates the store.
     *
     * @param control the control database's client
     * @param cells routes a shard to its cell
     * @param json encodes the stored response envelope
     */
    public JdbcIdempotencyStore(JdbcClient control, CellRouter cells, JsonMapper json) {
        this.control = Objects.requireNonNull(control, "control");
        this.cells = Objects.requireNonNull(cells, "cells");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public boolean insert(Location location, TeamId team, String key, byte[] requestHash, Instant at) {
        return jdbc(location)
                        .sql(INSERT)
                        .param("teamId", team.value())
                        .param("key", key)
                        .param("requestHash", requestHash)
                        .param("at", utc(at))
                        .update()
                == 1;
    }

    @Override
    public Optional<StoredRecord> find(Location location, TeamId team, String key) {
        return jdbc(location)
                .sql(FIND)
                .param("teamId", team.value())
                .param("key", key)
                .query((rs, row) -> {
                    boolean done = rs.getShort("status") == 2;
                    String envelope = rs.getString("response_body");
                    StoredResponse response =
                            done && envelope != null ? decode(rs.getShort("http_status"), envelope) : null;
                    return new StoredRecord(
                            rs.getBytes("request_hash"),
                            done,
                            response,
                            rs.getObject("created_at", OffsetDateTime.class).toInstant());
                })
                .optional();
    }

    @Override
    public boolean takeOver(
            Location location, TeamId team, String key, Instant seenCreatedAt, byte[] requestHash, Instant at) {
        return jdbc(location)
                        .sql(TAKE_OVER)
                        .param("teamId", team.value())
                        .param("key", key)
                        .param("seenCreatedAt", utc(seenCreatedAt))
                        .param("requestHash", requestHash)
                        .param("at", utc(at))
                        .update()
                == 1;
    }

    @Override
    public boolean complete(Location location, TeamId team, String key, Instant claimedAt, StoredResponse response) {
        return jdbc(location)
                        .sql(COMPLETE)
                        .param("teamId", team.value())
                        .param("key", key)
                        .param("claimedAt", utc(claimedAt))
                        .param("httpStatus", (short) response.status())
                        .param("responseBody", encode(response))
                        .update()
                == 1;
    }

    @Override
    public void release(Location location, TeamId team, String key, Instant claimedAt) {
        jdbc(location)
                .sql(RELEASE)
                .param("teamId", team.value())
                .param("key", key)
                .param("claimedAt", utc(claimedAt))
                .update();
    }

    private JdbcClient jdbc(Location location) {
        Shard shard = location.shard();
        return shard == null ? control : cells.forShard(shard).jdbc();
    }

    private String encode(StoredResponse response) {
        ObjectNode envelope = json.createObjectNode();
        envelope.put("contentType", response.contentType());
        ObjectNode headers = envelope.putObject("headers");
        response.headers().forEach(headers::put);
        envelope.put("body", response.body());
        return json.writeValueAsString(envelope);
    }

    private StoredResponse decode(int status, String text) {
        JsonNode envelope = json.readTree(text);
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode stored = envelope.path("headers");
        for (String name : StoredResponse.REPLAYED_HEADERS) {
            JsonNode value = stored.get(name);
            if (value != null && value.isString()) {
                headers.put(name, value.asString());
            }
        }
        return new StoredResponse(status, text(envelope, "contentType"), headers, text(envelope, "body"));
    }

    private static @Nullable String text(JsonNode envelope, String field) {
        JsonNode value = envelope.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
