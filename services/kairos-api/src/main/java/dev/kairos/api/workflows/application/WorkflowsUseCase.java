package dev.kairos.api.workflows.application;

import dev.kairos.api.team.TeamProperties;
import dev.kairos.api.team.application.TeamQuotas;
import dev.kairos.api.team.application.TeamStore;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.NotFoundException;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.shard.ShardKey;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Team-scoped application operations for LLD APIs 46–58. */
public final class WorkflowsUseCase {
    private final WorkflowStore store;
    private final WorkflowEndpointCatalog endpoints;
    private final TeamStore teams;
    private final TeamProperties properties;
    private final KairosIdFactory ids;
    private final Clock clock;
    private final JsonMapper json;
    private final JsonMapper strict = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .build();

    public WorkflowsUseCase(
            WorkflowStore store,
            WorkflowEndpointCatalog endpoints,
            TeamStore teams,
            TeamProperties properties,
            KairosIdFactory ids,
            Clock clock,
            JsonMapper json) {
        this.store = store;
        this.endpoints = endpoints;
        this.teams = teams;
        this.properties = properties;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
    }

    public WorkflowStore.Workflow create(TeamId team, String actor, String body) {
        Instant now = now();
        WorkflowDefinition definition = definition(team, body, now);
        return store.create(
                team,
                ids.newWorkflowId(ShardKey.of(team.value(), definition.name())).value(),
                actor,
                definition,
                now);
    }

    public WorkflowStore.Workflow update(TeamId team, UUID id, long expected, String body) {
        var current = get(team, id);
        Instant now = now();
        WorkflowDefinition definition = definition(team, body, now);
        if (!definition.name().equals(current.name()))
            throw new ValidationFailedException(
                    ErrorCode.NAME_IMMUTABLE, "A workflow's name cannot be changed.", List.of());
        return store.update(team, id, expected, definition, now);
    }

    public WorkflowStore.Workflow get(TeamId team, UUID id) {
        return store.find(team, id).orElseThrow(() -> new NotFoundException("The workflow does not exist."));
    }

    public Page list(TeamId team, @Nullable String status, WorkflowStore.@Nullable Key after, int limit) {
        List<WorkflowStore.Workflow> rows = store.list(team, status, after, limit + 1);
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        return new Page(
                items,
                more
                        ? new WorkflowStore.Key(
                                items.getLast().createdAt(), items.getLast().id())
                        : null);
    }

    public WorkflowStore.Workflow change(TeamId team, UUID id, WorkflowStore.Change change) {
        return store.change(team, id, change, now());
    }

    public WorkflowStore.Run runNow(TeamId team, UUID id) {
        return store.runNow(team, id, now());
    }

    public Runs runs(
            TeamId team, UUID id, WorkflowStore.RunFilter filter, WorkflowStore.@Nullable RunKey after, int limit) {
        get(team, id);
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to()))
            throw ValidationFailedException.of("from", FieldError.RANGE);
        var rows = store.runs(team, id, filter, after, limit + 1);
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        return new Runs(
                items,
                more
                        ? new WorkflowStore.RunKey(
                                items.getLast().scheduledAt(), items.getLast().workflowRunId())
                        : null);
    }

    public WorkflowStore.Run getRun(TeamId team, UUID id) {
        return store.findRun(team, id).orElseThrow(() -> new NotFoundException("The workflow run does not exist."));
    }

    public WorkflowStore.Run cancelRun(TeamId team, UUID id) {
        return store.cancelRun(team, id, now());
    }

    public WorkflowStore.Retry retry(TeamId team, UUID id, String actor) {
        return store.retry(team, id, actor, now());
    }

    private WorkflowDefinition definition(TeamId team, String body, Instant now) {
        if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > WorkflowDefinition.MAX_BYTES)
            throw ValidationFailedException.of("", "too-large");
        JsonNode parsed;
        try {
            parsed = strict.readTree(body);
        } catch (tools.jackson.core.JacksonException e) {
            throw ValidationFailedException.of("tasks", FieldError.FORMAT);
        }
        TeamQuotas defaults = properties.defaultQuotas().toQuotas();
        int quota = teams.findTeam(team)
                .map(value -> TeamQuotas.withDefaults(value.quotas(), defaults).maxWorkflowTasks())
                .orElse(defaults.maxWorkflowTasks());
        return WorkflowDefinition.parse(parsed, now, endpoints, team, quota, json);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public record Page(List<WorkflowStore.Workflow> items, WorkflowStore.@Nullable Key next) {}

    public record Runs(List<WorkflowStore.Run> items, WorkflowStore.@Nullable RunKey next) {}
}
