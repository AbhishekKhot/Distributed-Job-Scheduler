package dev.kairos.api.cabundles.application;

import dev.kairos.api.secrets.application.ResourceNames;
import dev.kairos.domain.id.TeamId;
import java.time.Clock;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** LLD APIs 20–22: validate trust anchors before storing them; lists return metadata only. */
public final class CaBundlesUseCase {
    private final CaBundleStore store;
    private final Clock clock;

    public CaBundlesUseCase(CaBundleStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public CaBundleLookup.Metadata create(TeamId team, String actor, String name, @Nullable String pem) {
        ResourceNames.check(name);
        CaCertificates.validate(pem, clock.instant());
        return store.create(team, actor, name, pem, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    public Result list(TeamId team, @Nullable String after, int limit) {
        List<CaBundleLookup.Metadata> rows = store.list(team, after, limit + 1);
        List<CaBundleLookup.Metadata> page = rows.size() > limit ? rows.subList(0, limit) : rows;
        return new Result(page, rows.size() > limit ? page.getLast().name() : null);
    }

    public void delete(TeamId team, String actor, String name) {
        store.delete(team, actor, ResourceNames.check(name));
    }

    public record Result(
            List<CaBundleLookup.Metadata> items, @Nullable String next) {
        public Result {
            items = List.copyOf(items);
        }
    }
}
