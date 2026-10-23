package dev.kairos.api.cabundles.application;

import dev.kairos.domain.id.TeamId;
import java.time.Instant;
import java.util.Optional;

/** Metadata for endpoint reference validation; lock the team's row in the same control transaction first. */
public interface CaBundleLookup {
    Optional<Metadata> find(TeamId team, String name);

    record Metadata(String name, Instant createdAt) {}
}
