package dev.kairos.api.audit.application;

import dev.kairos.api.audit.application.AuditStore.Cursor;
import dev.kairos.api.audit.application.AuditStore.Event;
import dev.kairos.api.audit.application.AuditStore.Filter;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.TeamId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** LLD API 24: bounded team-scoped audit history, with filters preserved across cursor pages. */
public final class AuditQueryUseCase {
    private final AuditStore store;
    private final Clock clock;

    public AuditQueryUseCase(AuditStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public Result list(
            TeamId team,
            @Nullable Instant from,
            @Nullable Instant to,
            @Nullable String action,
            @Nullable Cursor cursor,
            int limit) {
        if (cursor != null) {
            Filter original = cursor.filter();
            if ((from != null && !from.equals(original.from()))
                    || (to != null && !to.equals(original.to()))
                    || (action != null && !Objects.equals(action, original.action()))) {
                throw ValidationFailedException.of("cursor", FieldError.FORMAT);
            }
            from = original.from();
            to = original.to();
            action = original.action();
        }
        Instant now = clock.instant();
        Instant upper = to == null ? now : to;
        if (upper.isAfter(now) || upper.isBefore(now.minus(Duration.ofDays(90)))) {
            throw ValidationFailedException.of("to", "out-of-range");
        }
        Instant lower = from == null ? upper.minus(Duration.ofDays(1)) : from;
        if (lower.isBefore(now.minus(Duration.ofDays(90))) || lower.isAfter(now)) {
            throw ValidationFailedException.of("from", "out-of-range");
        }
        if (upper.isBefore(lower)) {
            throw ValidationFailedException.of("to", "out-of-range");
        }
        if (action != null && (action.length() > 64 || !action.matches("^[A-Z][A-Z0-9_]{0,63}$"))) {
            throw ValidationFailedException.of("action", FieldError.FORMAT);
        }
        Filter filter = new Filter(lower, upper, action);
        List<Event> rows = store.list(team, filter, cursor == null ? null : cursor.after(), limit + 1);
        List<Event> page = rows.size() > limit ? rows.subList(0, limit) : rows;
        return new Result(
                page, rows.size() > limit ? new Cursor(filter, page.getLast().key()) : null);
    }

    public record Result(List<Event> items, @Nullable Cursor next) {
        public Result {
            items = List.copyOf(items);
        }
    }
}
