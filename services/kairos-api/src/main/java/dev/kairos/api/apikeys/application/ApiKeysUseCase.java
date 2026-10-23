package dev.kairos.api.apikeys.application;

import dev.kairos.api.apikeys.application.ApiKeyStore.PageKey;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.NotFoundException;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import dev.kairos.security.SecretValue;
import dev.kairos.security.apikey.ApiKeyService;
import dev.kairos.security.apikey.IssuedApiKey;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Creates, lists and revokes the caller's team's keys (LLD APIs 13–15). Thread-safe. */
public final class ApiKeysUseCase {

    public static final String NOT_FOUND = "The API key does not exist.";
    private final ApiKeyStore store;
    private final ApiKeyService keys;
    private final Clock clock;

    public ApiKeysUseCase(ApiKeyStore store, ApiKeyService keys, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Created create(TeamId team, UserId user, String name, @Nullable Instant expiresAt) {
        if (name.isBlank() || name.length() > 64) {
            throw ValidationFailedException.of("name", FieldError.LENGTH);
        }
        Instant now = clock.instant();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw ValidationFailedException.of("expiresAt", FieldError.RANGE);
        }
        IssuedApiKey issued = keys.issue();
        store.create(team, user, name, issued, expiresAt, now);
        return new Created(issued.keyId(), name, issued.key(), expiresAt, now);
    }

    public KeyPage list(TeamId team, @Nullable PageKey after, int limit) {
        List<ApiKeyStore.Metadata> rows = store.list(team, after, limit + 1);
        List<ApiKeyStore.Metadata> page = rows.size() > limit ? rows.subList(0, limit) : rows;
        return new KeyPage(page, rows.size() > limit ? page.get(limit - 1).pageKey() : null);
    }

    public void revoke(TeamId team, UserId actor, String keyId) {
        if (!validKeyId(keyId) || !store.revoke(team, actor, keyId, clock.instant())) {
            throw new NotFoundException(NOT_FOUND);
        }
    }

    public static boolean validKeyId(String keyId) {
        if (keyId.length() != ApiKeyService.KEY_ID_LENGTH) {
            return false;
        }
        for (int i = 0; i < keyId.length(); i++) {
            char c = keyId.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    /** A key returned once. SecretValue keeps it out of diagnostic strings. */
    public record Created(
            String keyId,
            String name,
            SecretValue key,
            @Nullable Instant expiresAt,
            Instant createdAt) {}

    public record KeyPage(
            List<ApiKeyStore.Metadata> items, @Nullable PageKey next) {
        public KeyPage {
            items = List.copyOf(items);
        }
    }
}
