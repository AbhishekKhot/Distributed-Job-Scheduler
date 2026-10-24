package dev.kairos.api.endpoints.application;

import dev.kairos.domain.id.TeamId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Team-scoped endpoint persistence. Mutations and their audits are atomic. */
public interface EndpointStore {
    Endpoint find(TeamId team, UUID id);

    List<Endpoint> list(TeamId team, @Nullable String status, @Nullable Key after, int limit);

    Endpoint create(TeamId team, String actor, UUID id, String name, EndpointSpec spec, Instant now);

    Endpoint update(TeamId team, String actor, UUID id, long expectedVersion, EndpointSpec spec, Instant now);

    void delete(TeamId team, String actor, UUID id);

    Endpoint transition(TeamId team, String actor, UUID id, String targetStatus, @Nullable String reason, Instant now);

    record Endpoint(
            UUID id,
            String name,
            EndpointSpec spec,
            String status,
            @Nullable String statusReason,
            long version,
            Instant createdAt,
            Instant updatedAt) {}

    record Key(Instant createdAt, UUID id) {}
}
