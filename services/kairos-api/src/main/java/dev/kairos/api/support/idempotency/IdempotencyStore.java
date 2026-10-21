package dev.kairos.api.support.idempotency;

import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.shard.Shard;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Storage of idempotency records ({@code api_idempotency}) in the control database or a cell. */
public interface IdempotencyStore {

    /**
     * Inserts an in-progress record if the key is free.
     *
     * @param location where the record lives
     * @param team the caller's team
     * @param key the idempotency key
     * @param requestHash SHA-256 of the request
     * @param at the claim time, truncated to microseconds
     * @return whether the record was inserted
     */
    boolean insert(Location location, TeamId team, String key, byte[] requestHash, Instant at);

    /**
     * Reads a record.
     *
     * @param location where the record lives
     * @param team the caller's team
     * @param key the idempotency key
     * @return the record, or empty
     */
    Optional<StoredRecord> find(Location location, TeamId team, String key);

    /**
     * Restarts a record (expired, or in progress for too long) for a new request, if it is still the version read.
     *
     * @param location where the record lives
     * @param team the caller's team
     * @param key the idempotency key
     * @param seenCreatedAt the {@code created_at} that was read
     * @param requestHash SHA-256 of the new request
     * @param at the new claim time, truncated to microseconds
     * @return whether this request took the record over
     */
    boolean takeOver(Location location, TeamId team, String key, Instant seenCreatedAt, byte[] requestHash, Instant at);

    /**
     * Stores the response of a claimed request.
     *
     * @param location where the record lives
     * @param team the caller's team
     * @param key the idempotency key
     * @param claimedAt the claim time
     * @param response the response
     * @return whether the record was still this request's claim
     */
    boolean complete(Location location, TeamId team, String key, Instant claimedAt, StoredResponse response);

    /**
     * Deletes the in-progress record of a request that did not succeed, so the key can be used again.
     *
     * @param location where the record lives
     * @param team the caller's team
     * @param key the idempotency key
     * @param claimedAt the claim time
     */
    void release(Location location, TeamId team, String key, Instant claimedAt);

    /**
     * Where a record lives.
     *
     * @param shard the shard whose cell holds it, or {@code null} for the control database
     */
    record Location(@Nullable Shard shard) {

        /** The control database. */
        public static final Location CONTROL = new Location(null);

        /**
         * Returns the location of a shard's cell.
         *
         * @param shard the shard
         * @return the location
         */
        public static Location cell(Shard shard) {
            return new Location(Objects.requireNonNull(shard, "shard"));
        }
    }

    /**
     * A stored record.
     *
     * @param requestHash SHA-256 of the request that claimed it
     * @param done whether the response is stored
     * @param response the stored response, when done
     * @param createdAt when it was claimed
     */
    record StoredRecord(
            byte[] requestHash, boolean done, @Nullable StoredResponse response, Instant createdAt) {

        /** Copies the hash. */
        public StoredRecord {
            requestHash = requestHash.clone();
            Objects.requireNonNull(createdAt, "createdAt");
        }

        @Override
        public byte[] requestHash() {
            return requestHash.clone();
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof StoredRecord that
                    && java.util.Arrays.equals(requestHash, that.requestHash)
                    && done == that.done
                    && Objects.equals(response, that.response)
                    && createdAt.equals(that.createdAt);
        }

        @Override
        public int hashCode() {
            return Objects.hash(done, response, createdAt) * 31 + java.util.Arrays.hashCode(requestHash);
        }

        @Override
        public String toString() {
            return "StoredRecord[done=" + done + ", createdAt=" + createdAt + "]";
        }
    }
}
