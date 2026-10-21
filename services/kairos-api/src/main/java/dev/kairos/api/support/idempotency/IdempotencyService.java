package dev.kairos.api.support.idempotency;

import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.id.TeamId;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * The rules of idempotent requests on top of an {@link IdempotencyStore}.
 *
 * <ul>
 *   <li>A free key is claimed with an in-progress record, and the request runs.
 *   <li>A key whose record is done and was made by the same request (same SHA-256 of method, path and body) replays
 *       the stored response.
 *   <li>A key used with a different request is 409 {@code idempotency-key-reused}.
 *   <li>A key whose first request is still running is polled for up to {@code waitTimeout}: the stored response is
 *       replayed as soon as it exists, and if the first request failed and released the key, this request claims
 *       it. Still running after the wait is 409.
 *   <li>A record older than {@code ttl} (24h) is expired and taken over; an in-progress record older than
 *       {@code staleAfter} belongs to a request that crashed and is taken over too.
 * </ul>
 *
 * Thread-safe.
 */
public final class IdempotencyService {

    /** LLD message of {@link ErrorCode#IDEMPOTENCY_KEY_REUSED} for a different request. */
    public static final String REUSED_DETAIL = "This Idempotency-Key was already used with a different request body.";

    /** Message of {@link ErrorCode#IDEMPOTENCY_KEY_REUSED} while the first request is still running. */
    public static final String IN_PROGRESS_DETAIL =
            "A request with this Idempotency-Key is still being processed. Retry later.";

    private final IdempotencyStore store;
    private final Clock clock;
    private final Settings settings;
    private final Sleeper sleeper;

    /**
     * Creates the service.
     *
     * @param store the record store
     * @param clock the time source
     * @param settings the lifetimes and waits
     * @param sleeper pauses between polls of a running request
     */
    public IdempotencyService(IdempotencyStore store, Clock clock, Settings settings, Sleeper sleeper) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * Starts an idempotent request.
     *
     * @param location where the record lives
     * @param team the caller's team
     * @param key the idempotency key
     * @param requestHash SHA-256 of the request
     * @return whether to run the request or replay a stored response
     * @throws ConflictException 409 {@code idempotency-key-reused} for a different request or one still running
     */
    public Outcome begin(IdempotencyStore.Location location, TeamId team, String key, byte[] requestHash) {
        Instant deadline = clock.instant().plus(settings.waitTimeout());
        while (true) {
            Instant now = now();
            if (store.insert(location, team, key, requestHash, now)) {
                return new Outcome.Proceed(new ActiveClaim(location, team, key, now));
            }
            Optional<IdempotencyStore.StoredRecord> found = store.find(location, team, key);
            if (found.isEmpty()) {
                // Released between the insert and the read: try to claim it again.
                requireTimeLeft(deadline);
                continue;
            }
            IdempotencyStore.StoredRecord stored = found.get();
            boolean expired = stored.createdAt().isBefore(now.minus(settings.ttl()));
            boolean stale = !stored.done() && stored.createdAt().isBefore(now.minus(settings.staleAfter()));
            if (expired || stale) {
                if (store.takeOver(location, team, key, stored.createdAt(), requestHash, now)) {
                    return new Outcome.Proceed(new ActiveClaim(location, team, key, now));
                }
                requireTimeLeft(deadline);
                continue;
            }
            if (!MessageDigest.isEqual(stored.requestHash(), requestHash)) {
                throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_REUSED, REUSED_DETAIL);
            }
            StoredResponse response = stored.response();
            if (stored.done() && response != null) {
                return new Outcome.Replay(response);
            }
            requireTimeLeft(deadline);
            sleeper.sleep(settings.pollInterval());
        }
    }

    /**
     * Stores the response of a successful request so repeats replay it.
     *
     * @param claim the request's claim
     * @param response the response
     */
    public void finish(ActiveClaim claim, StoredResponse response) {
        store.complete(claim.location(), claim.team(), claim.key(), claim.claimedAt(), response);
    }

    /**
     * Frees the key of a request that did not succeed, so the client can retry with the same key.
     *
     * @param claim the request's claim
     */
    public void abandon(ActiveClaim claim) {
        store.release(claim.location(), claim.team(), claim.key(), claim.claimedAt());
    }

    private void requireTimeLeft(Instant deadline) {
        if (!clock.instant().isBefore(deadline)) {
            throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_REUSED, IN_PROGRESS_DETAIL);
        }
    }

    // The database keeps microseconds, so claim times are compared at that precision.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** What to do with an idempotent request. */
    public sealed interface Outcome {

        /**
         * Run the request; its response is stored under the claim.
         *
         * @param claim the claim
         */
        record Proceed(ActiveClaim claim) implements Outcome {}

        /**
         * Answer with the stored response without running the request.
         *
         * @param response the stored response
         */
        record Replay(StoredResponse response) implements Outcome {}
    }

    /**
     * A key claimed by a running request.
     *
     * @param location where the record lives
     * @param team the caller's team
     * @param key the idempotency key
     * @param claimedAt the claim time, the record's {@code created_at}
     */
    public record ActiveClaim(IdempotencyStore.Location location, TeamId team, String key, Instant claimedAt) {}

    /**
     * The lifetimes and waits.
     *
     * @param ttl how long a record is honoured (24h)
     * @param staleAfter after how long an in-progress record is considered abandoned
     * @param waitTimeout how long a concurrent repeat waits for the first request
     * @param pollInterval the pause between checks while waiting
     */
    public record Settings(Duration ttl, Duration staleAfter, Duration waitTimeout, Duration pollInterval) {

        /**
         * Validates the settings.
         *
         * @throws IllegalArgumentException if a duration is not positive
         */
        public Settings {
            for (Duration duration : new Duration[] {ttl, staleAfter, waitTimeout, pollInterval}) {
                if (Objects.requireNonNull(duration, "duration").isNegative() || duration.isZero()) {
                    throw new IllegalArgumentException("idempotency durations must be positive");
                }
            }
        }
    }

    /** Pauses the current thread. */
    @FunctionalInterface
    public interface Sleeper {

        /**
         * Sleeps.
         *
         * @param duration how long
         */
        void sleep(Duration duration);

        /**
         * Returns a sleeper on {@link Thread#sleep(Duration)} that restores the interrupt flag.
         *
         * @return the sleeper
         */
        static Sleeper threadSleep() {
            return duration -> {
                try {
                    Thread.sleep(duration);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for an idempotent request", e);
                }
            };
        }
    }
}
