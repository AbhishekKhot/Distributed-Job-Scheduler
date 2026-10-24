package dev.kairos.api.endpoints.application;

import dev.kairos.api.endpoints.application.EndpointStore.Key;
import dev.kairos.api.support.ratelimit.RateLimit;
import dev.kairos.api.support.ratelimit.RateLimiter;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.id.TeamId;
import dev.kairos.http.policy.TargetPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Endpoint management use cases (LLD APIs 25–29). */
public final class EndpointsUseCase {
    private final EndpointStore store;
    private final TargetPolicy targets;
    private final KairosIdFactory ids;
    private final Clock clock;
    private final EndpointScheduleControl schedules;
    private final EndpointTestGateway testGateway;
    private final RateLimiter rateLimiter;
    private final EndpointHealthReader healthReader;

    public EndpointsUseCase(
            EndpointStore store,
            TargetPolicy targets,
            KairosIdFactory ids,
            Clock clock,
            EndpointScheduleControl schedules,
            EndpointTestGateway testGateway,
            RateLimiter rateLimiter,
            EndpointHealthReader healthReader) {
        this.store = store;
        this.targets = targets;
        this.ids = ids;
        this.clock = clock;
        this.schedules = schedules;
        this.testGateway = testGateway;
        this.rateLimiter = rateLimiter;
        this.healthReader = healthReader;
    }

    public EndpointStore.Endpoint create(TeamId team, String actor, String name, EndpointSpec spec) {
        checkName(name);
        spec.validateTarget(targets);
        return store.create(team, actor, ids.newId(), name, spec, now());
    }

    public EndpointStore.Endpoint get(TeamId team, UUID id) {
        return store.find(team, id);
    }

    public ListResult list(TeamId team, @Nullable String status, @Nullable Key after, int limit) {
        if (status != null && !List.of("ACTIVE", "SUSPENDED", "DISABLED").contains(status)) {
            throw dev.kairos.domain.error.ValidationFailedException.of(
                    "status", dev.kairos.domain.error.FieldError.FORMAT);
        }
        List<EndpointStore.Endpoint> rows = store.list(team, status, after, limit + 1);
        List<EndpointStore.Endpoint> items = rows.size() > limit ? rows.subList(0, limit) : rows;
        Key next = rows.size() > limit
                ? new Key(items.getLast().createdAt(), items.getLast().id())
                : null;
        return new ListResult(items, next);
    }

    public EndpointStore.Endpoint update(
            TeamId team, String actor, UUID id, String requestedName, long expectedVersion, EndpointSpec spec) {
        if (!store.find(team, id).name().equals(requestedName)) {
            throw new ValidationFailedException(
                    ErrorCode.NAME_IMMUTABLE, "The endpoint name cannot change.", List.of());
        }
        spec.validateTarget(targets);
        return store.update(team, actor, id, expectedVersion, spec, now());
    }

    public void delete(TeamId team, String actor, UUID id) {
        store.delete(team, actor, id);
    }

    public EndpointStore.Endpoint suspend(TeamId team, String actor, UUID id, @Nullable String reason) {
        EndpointStore.Endpoint endpoint = store.transition(team, actor, id, "SUSPENDED", reason, now());
        schedules.reconcile(id, true);
        return endpoint;
    }

    public EndpointStore.Endpoint resume(TeamId team, String actor, UUID id) {
        EndpointStore.Endpoint endpoint = store.transition(team, actor, id, "ACTIVE", null, now());
        schedules.reconcile(id, false);
        return endpoint;
    }

    public EndpointTestGateway.Result test(TeamId team, UUID id, EndpointTestGateway.Request request) {
        EndpointStore.Endpoint endpoint = store.find(team, id);
        if (!"ACTIVE".equals(endpoint.status())) {
            throw new dev.kairos.domain.error.ConflictException(
                    ErrorCode.INVALID_STATE, "A suspended endpoint cannot be tested.");
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]{0,63})}")
                .matcher(endpoint.spec().urlTemplate());
        while (matcher.find()) {
            if (!request.pathParams().containsKey(matcher.group(1))) {
                throw new ValidationFailedException(
                        ErrorCode.RENDER_FAILED, "Path parameter " + matcher.group(1) + " is missing.", List.of());
            }
        }
        rateLimiter.consume(RateLimit.ENDPOINT_TEST_PER_TEAM, team.toString());
        return testGateway.test(team, id, request);
    }

    public EndpointHealthReader.EndpointHealth health(TeamId team, UUID id, @Nullable String requestedWindow) {
        store.find(team, id);
        String window = requestedWindow == null ? "1h" : requestedWindow;
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("([0-9]{1,3})(m|h)").matcher(window);
        if (!matcher.matches()) throw dev.kairos.domain.error.ValidationFailedException.of("window", "range");
        long amount = Long.parseLong(matcher.group(1));
        java.time.Duration duration = "m".equals(matcher.group(2))
                ? java.time.Duration.ofMinutes(amount)
                : java.time.Duration.ofHours(amount);
        if (duration.compareTo(java.time.Duration.ofMinutes(5)) < 0
                || duration.compareTo(java.time.Duration.ofHours(24)) > 0) {
            throw dev.kairos.domain.error.ValidationFailedException.of("window", "range");
        }
        return healthReader.read(team, id, window, duration);
    }

    private Instant now() {
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private static void checkName(String name) {
        if (name == null || !name.matches("^[a-z][a-z0-9-]{1,62}$")) {
            throw dev.kairos.domain.error.ValidationFailedException.of(
                    "name", dev.kairos.domain.error.FieldError.PATTERN);
        }
    }

    public record ListResult(
            List<EndpointStore.Endpoint> items, @Nullable Key next) {
        public ListResult {
            items = List.copyOf(items);
        }
    }
}
