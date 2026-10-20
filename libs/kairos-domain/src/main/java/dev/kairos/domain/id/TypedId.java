package dev.kairos.domain.id;

import java.util.UUID;

/**
 * A typed Kairos identifier: a UUIDv7 wrapped in a record so that ids of different resources
 * cannot be mixed up.
 *
 * <p>Every implementation validates the layout on construction and prints as the canonical UUID
 * text, so {@code toString()} is safe to use in URLs, SQL parameters and shard keys.
 */
public sealed interface TypedId permits TeamId, UserId, EndpointId, ShardedId {

    /**
     * Returns the underlying UUID.
     *
     * @return the UUID
     */
    UUID value();
}
