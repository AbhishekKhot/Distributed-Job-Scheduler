package dev.kairos.api.jobs.application;

import dev.kairos.api.endpoints.application.EndpointSpec;
import dev.kairos.domain.id.TeamId;
import java.util.Optional;

/** Reads the team-owned endpoint version used to validate and create a job run. */
public interface JobEndpointCatalog {
    /** Finds an endpoint by its immutable team-scoped name. */
    Optional<Endpoint> find(TeamId team, String name);

    /** Public endpoint metadata and its current immutable call specification. */
    record Endpoint(java.util.UUID id, String name, String status, long version, EndpointSpec spec) {}
}
