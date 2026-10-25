package dev.kairos.api.workflows.application;

import dev.kairos.api.endpoints.application.EndpointSpec;
import dev.kairos.domain.id.TeamId;
import java.util.Optional;
import java.util.UUID;

/** Team-scoped current and immutable endpoint configurations used by workflow tasks. */
public interface WorkflowEndpointCatalog {
    Optional<Endpoint> find(TeamId team, String name);

    Endpoint pinned(TeamId team, UUID id, int version);

    record Endpoint(UUID id, String name, String status, int version, EndpointSpec spec) {}
}
