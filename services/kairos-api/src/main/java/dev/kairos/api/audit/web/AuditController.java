package dev.kairos.api.audit.web;

import dev.kairos.api.audit.application.AuditQueryUseCase;
import dev.kairos.api.audit.application.AuditStore;
import dev.kairos.api.audit.application.AuditStore.Cursor;
import dev.kairos.api.security.TeamContext;
import dev.kairos.api.support.paging.CursorCodec;
import dev.kairos.api.support.paging.Page;
import dev.kairos.api.support.paging.PageLimit;
import dev.kairos.api.support.problem.Problem;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.TeamId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Team-scoped security history available to user tokens and API keys (LLD API 24). */
@RestController
@Tag(name = "Audit log", description = "Read the team's security-relevant changes.")
public class AuditController {
    private static final String LIST = "audit-log";
    private final AuditQueryUseCase queries;
    private final CursorCodec cursors;

    public AuditController(AuditQueryUseCase queries, CursorCodec cursors) {
        this.queries = queries;
        this.cursors = cursors;
    }

    @GetMapping(path = "/v1/audit-log", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Read the audit log",
            description = "Returns the caller's team's events, newest first."
                    + " Defaults to the last 24 hours; supports up to 90 days of history. Bounds are inclusive."
                    + " Omitted filters on subsequent pages retain the cursor's original range and action.")
    @ApiResponse(
            responseCode = "200",
            description = "One page of events",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                    {"items":[{"at":"2026-10-14T09:30:00Z","actor":"user:0192e7a9-1f3c-7d8e-9a0b-1c2d3e4f5a6b",
                     "action":"JOB_DELETED","resourceType":"job","resourceId":"0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9",
                     "details":{"name":"nightly-order-sync"}}],"nextCursor":null}
                    """)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid range, action, limit or cursor",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(value = """
                            {"type":"/errors/validation","title":"Invalid request","status":400,
                             "detail":"One or more fields are invalid.","errors":[{"field":"from","code":"out-of-range"}]}
                            """)))
    public Page<EventResponse> list(
            TeamContext team,
            @Parameter(description = "UTC RFC 3339 inclusive start, at most 90 days ago.")
                    @RequestParam(name = "from", required = false)
                    @Nullable String from,
            @Parameter(description = "UTC RFC 3339 inclusive end; defaults to now.")
                    @RequestParam(name = "to", required = false)
                    @Nullable String to,
            @Parameter(description = "Exact action, for example JOB_DELETED.")
                    @RequestParam(name = "action", required = false)
                    @Nullable String action,
            @Parameter(description = "Page size 1–200; default 50.")
                    @RequestParam(name = "limit", required = false)
                    @Nullable Integer limit,
            @Parameter(description = "Opaque nextCursor; bound to this team, range and action.")
                    @RequestParam(name = "cursor", required = false)
                    @Nullable String cursor) {
        AuditQueryUseCase.Result result = queries.list(
                team.teamId(),
                parseTime("from", from),
                parseTime("to", to),
                action,
                cursor == null ? null : decode(team.teamId(), cursor),
                PageLimit.resolve(limit));
        Cursor next = result.next();
        return new Page<>(
                result.items().stream().map(EventResponse::of).toList(),
                next == null ? null : encode(team.teamId(), next));
    }

    private String encode(TeamId team, Cursor cursor) {
        return cursors.encode(
                LIST,
                team,
                List.of(
                        cursor.filter().from().toString(),
                        cursor.filter().to().toString(),
                        cursor.filter().action() == null ? "" : cursor.filter().action(),
                        cursor.after().at().toString(),
                        cursor.after().id().toString()));
    }

    private Cursor decode(TeamId team, String cursor) {
        List<String> values = cursors.decode(LIST, team, cursor);
        if (values.size() != 5) {
            throw ValidationFailedException.of("cursor", FieldError.FORMAT);
        }
        try {
            return new Cursor(
                    new AuditStore.Filter(
                            Instant.parse(values.get(0)),
                            Instant.parse(values.get(1)),
                            values.get(2).isEmpty() ? null : values.get(2)),
                    new AuditStore.Key(Instant.parse(values.get(3)), UUID.fromString(values.get(4))));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw ValidationFailedException.of("cursor", FieldError.FORMAT);
        }
    }

    private static @Nullable Instant parseTime(String field, @Nullable String value) {
        if (value == null) return null;
        if (value.length() > 64) throw ValidationFailedException.of(field, FieldError.FORMAT);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw ValidationFailedException.of(field, FieldError.FORMAT);
        }
    }

    @Schema(name = "AuditEvent")
    public record EventResponse(
            Instant at,
            String actor,
            String action,
            @Nullable String resourceType,
            @Nullable String resourceId,
            Map<String, Object> details) {
        static EventResponse of(AuditStore.Event event) {
            return new EventResponse(
                    event.at(),
                    event.actor(),
                    event.action(),
                    event.resourceType(),
                    event.resourceId(),
                    event.details());
        }
    }
}
