package dev.kairos.api.runs.application;

import dev.kairos.api.runs.application.RunQueries.Key;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.TeamId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Validates run filters and executes the team-scoped read use cases. */
public final class RunQueryUseCase {
    private final RunQueries queries;
    private final Clock clock;

    /** Creates the use case. */
    public RunQueryUseCase(RunQueries queries, Clock clock) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Lists matching runs. */
    public List<RunQueries.RunSummary> list(TeamId team, RunQueries.Filter filter, @Nullable Key after, int limit) {
        validate(filter);
        return queries.list(team, filter, after, limit);
    }

    /** Reads a run and its attempts. */
    public Optional<RunQueries.RunDetails> get(TeamId team, UUID runId) {
        return queries.get(team, runId);
    }

    /** Requests cancellation and returns the current run state. */
    public Optional<RunQueries.RunSummary> cancel(TeamId team, UUID runId) {
        return queries.cancel(team, runId);
    }

    private void validate(RunQueries.Filter filter) {
        Instant now = clock.instant();
        Instant oldest = now.minus(Duration.ofDays(7));
        if (filter.from() != null
                && (filter.from().isBefore(oldest) || filter.from().isAfter(now))) {
            throw ValidationFailedException.of("from", "out-of-range");
        }
        if (filter.to() != null && (filter.to().isBefore(oldest) || filter.to().isAfter(now))) {
            throw ValidationFailedException.of("to", "out-of-range");
        }
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw ValidationFailedException.of("to", FieldError.RANGE);
        }
        if (filter.endpoint() != null
                && (filter.endpoint().length() > 63 || !filter.endpoint().matches("^[a-z][a-z0-9-]{1,62}$"))) {
            throw ValidationFailedException.of("endpoint", FieldError.FORMAT);
        }
    }
}
