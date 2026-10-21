package dev.kairos.api.support.idempotency;

import dev.kairos.domain.id.TeamId;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** An {@link IdempotencyStore} in memory, with the same conditional semantics as the SQL store. */
final class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, StoredRecord> records = new ConcurrentHashMap<>();

    @Override
    public boolean insert(Location location, TeamId team, String key, byte[] requestHash, Instant at) {
        return records.putIfAbsent(id(location, team, key), new StoredRecord(requestHash, false, null, at)) == null;
    }

    @Override
    public Optional<StoredRecord> find(Location location, TeamId team, String key) {
        return Optional.ofNullable(records.get(id(location, team, key)));
    }

    @Override
    public boolean takeOver(
            Location location, TeamId team, String key, Instant seenCreatedAt, byte[] requestHash, Instant at) {
        return replaceIf(location, team, key, seenCreatedAt, false, new StoredRecord(requestHash, false, null, at));
    }

    @Override
    public boolean complete(Location location, TeamId team, String key, Instant claimedAt, StoredResponse response) {
        StoredRecord current = records.get(id(location, team, key));
        return current != null
                && replaceIf(
                        location,
                        team,
                        key,
                        claimedAt,
                        true,
                        new StoredRecord(current.requestHash(), true, response, claimedAt));
    }

    @Override
    public void release(Location location, TeamId team, String key, Instant claimedAt) {
        records.computeIfPresent(
                id(location, team, key),
                (k, record) -> !record.done() && record.createdAt().equals(claimedAt) ? null : record);
    }

    void put(Location location, TeamId team, String key, StoredRecord record) {
        records.put(id(location, team, key), record);
    }

    private boolean replaceIf(
            Location location, TeamId team, String key, Instant createdAt, boolean onlyInProgress, StoredRecord next) {
        boolean[] replaced = {false};
        records.computeIfPresent(id(location, team, key), (k, record) -> {
            if (record.createdAt().equals(createdAt) && (!onlyInProgress || !record.done())) {
                replaced[0] = true;
                return next;
            }
            return record;
        });
        return replaced[0];
    }

    private static String id(Location location, TeamId team, String key) {
        @Nullable Object shard = location.shard();
        return shard + "|" + team.value() + "|" + key;
    }
}
