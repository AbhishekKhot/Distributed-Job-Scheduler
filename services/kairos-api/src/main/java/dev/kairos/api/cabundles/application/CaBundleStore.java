package dev.kairos.api.cabundles.application;

import dev.kairos.domain.id.TeamId;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Team-scoped certificate bundles; mutation and audit entries are atomic. */
public interface CaBundleStore extends CaBundleLookup {
    Metadata create(TeamId team, String actor, String name, String pem, Instant now);

    List<Metadata> list(TeamId team, @Nullable String after, int limit);

    void delete(TeamId team, String actor, String name);
}
