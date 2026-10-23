package dev.kairos.api.secrets.application;

import dev.kairos.api.support.problem.ApiException;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.NotFoundException;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.TeamId;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** LLD APIs 16–19: credentials are encrypted before transactions and never returned. Thread-safe. */
public final class SecretsUseCase {
    public static final String NOT_FOUND = "The secret does not exist.";
    private final SecretStore store;
    private final SecretSealer sealer;
    private final Clock clock;

    public SecretsUseCase(SecretStore store, SecretSealer sealer, Clock clock) {
        this.store = store;
        this.sealer = sealer;
        this.clock = clock;
    }

    public SecretLookup.Metadata create(
            TeamId team, String actor, String name, @Nullable String kind, @Nullable CredentialValue value) {
        ResourceNames.check(name);
        CredentialValue.Kind type;
        try {
            type = CredentialValue.Kind.valueOf(kind == null ? "" : kind);
        } catch (IllegalArgumentException failure) {
            throw ValidationFailedException.of("kind", "enum");
        }
        validate(value, type, true);
        byte[] ciphertext = sealer.seal(team, name, 1, value);
        return store.create(
                team, actor, name, type, ciphertext, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    public SecretLookup.Metadata rotate(TeamId team, String actor, String name, @Nullable CredentialValue value) {
        ResourceNames.check(name);
        for (int attempt = 0; attempt < 8; attempt++) {
            SecretLookup.Metadata before = store.find(team, name).orElseThrow(() -> new NotFoundException(NOT_FOUND));
            validate(value, before.kind(), false);
            if (before.version() == Integer.MAX_VALUE) throw ApiException.unavailable(Duration.ofSeconds(1));
            byte[] ciphertext = sealer.seal(team, name, before.version() + 1, value);
            var updated = store.rotate(team, actor, before, ciphertext, clock.instant());
            if (updated.isPresent()) return updated.get();
        }
        throw ApiException.unavailable(Duration.ofSeconds(1));
    }

    public Result list(TeamId team, @Nullable String after, int limit) {
        List<SecretLookup.Metadata> rows = store.list(team, after, limit + 1);
        List<SecretLookup.Metadata> page = rows.size() > limit ? rows.subList(0, limit) : rows;
        return new Result(page, rows.size() > limit ? page.getLast().name() : null);
    }

    public void delete(TeamId team, String actor, String name) {
        store.delete(team, actor, ResourceNames.check(name));
    }

    private static void validate(@Nullable CredentialValue value, CredentialValue.Kind kind, boolean creating) {
        if (value == null)
            throw CredentialValue.invalid(List.of(new FieldError("value", FieldError.REQUIRED)), creating);
        value.validate(kind, creating);
    }

    public record Result(
            List<SecretLookup.Metadata> items, @Nullable String next) {
        public Result {
            items = List.copyOf(items);
        }
    }
}
