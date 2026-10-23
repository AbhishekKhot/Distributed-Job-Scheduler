package dev.kairos.api.apikeys.infrastructure;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.kairos.api.apikeys.application.ApiKeyStore;
import dev.kairos.api.apikeys.application.ApiKeyUsage;
import dev.kairos.domain.id.TeamId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Coalesces usage updates per key for one minute, with bounded memory. The SQL guard enforces the write interval
 * across replicas and cache evictions too. Failed updates are not cached, so a later request retries. Thread-safe.
 */
public final class ThrottledApiKeyUsage implements ApiKeyUsage {
    private final ApiKeyStore store;
    private final Clock clock;
    private final Cache<Key, Instant> recorded;

    public ThrottledApiKeyUsage(ApiKeyStore store, Clock clock, long maximumSize) {
        this.store = store;
        this.clock = clock;
        this.recorded = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(Duration.ofMinutes(1))
                .ticker(() -> {
                    Instant now = clock.instant();
                    return now.getEpochSecond() * 1_000_000_000L + now.getNano();
                })
                .build();
    }

    @Override
    public void record(TeamId team, String keyId) {
        recorded.get(new Key(team, keyId), key -> {
            Instant now = clock.instant();
            store.recordUse(key.team(), key.id(), now);
            return now;
        });
    }

    private record Key(TeamId team, String id) {}
}
