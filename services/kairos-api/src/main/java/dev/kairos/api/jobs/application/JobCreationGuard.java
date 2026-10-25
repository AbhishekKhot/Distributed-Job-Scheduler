package dev.kairos.api.jobs.application;

import dev.kairos.domain.id.TeamId;
import java.util.function.Supplier;

/** Serializes a team's job quota check and creation across API instances. */
public interface JobCreationGuard {
    <T> T execute(TeamId team, Supplier<T> work);
}
