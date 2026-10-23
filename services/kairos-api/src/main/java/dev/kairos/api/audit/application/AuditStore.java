package dev.kairos.api.audit.application;

import dev.kairos.domain.id.TeamId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Team-scoped, newest-first audit queries. The id breaks ties between events at the same instant. */
public interface AuditStore {
    List<Event> list(TeamId team, Filter filter, @Nullable Key after, int limit);

    record Filter(Instant from, Instant to, @Nullable String action) {}

    record Key(Instant at, UUID id) {}

    record Cursor(Filter filter, Key after) {}

    record Event(
            UUID id,
            Instant at,
            String actor,
            String action,
            @Nullable String resourceType,
            @Nullable String resourceId,
            Map<String, Object> details) {
        public Event {
            details = Map.copyOf(details);
        }

        public Key key() {
            return new Key(at, id);
        }
    }
}
