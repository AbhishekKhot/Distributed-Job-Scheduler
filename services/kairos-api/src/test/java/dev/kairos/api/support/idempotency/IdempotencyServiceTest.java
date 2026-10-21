package dev.kairos.api.support.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.api.support.idempotency.IdempotencyService.Outcome;
import dev.kairos.api.support.idempotency.IdempotencyStore.Location;
import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.id.TeamId;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.TestIds;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IdempotencyServiceTest {

    private static final byte[] HASH = {1, 2, 3};
    private static final StoredResponse RESPONSE =
            new StoredResponse(201, "application/json", Map.of("Location", "/v1/jobs/1"), "{\"id\":1}");

    private final MutableClock clock = MutableClock.at(Instant.parse("2026-10-21T10:00:00.123456789Z"));
    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
    private final TeamId team = TestIds.teamId();
    private final IdempotencyService service = new IdempotencyService(
            store,
            clock,
            new IdempotencyService.Settings(
                    Duration.ofHours(24), Duration.ofSeconds(60), Duration.ofSeconds(2), Duration.ofMillis(50)),
            clock::advance);

    @Test
    void aFreeKeyIsClaimedAtMicrosecondPrecision() {
        Outcome outcome = service.begin(Location.CONTROL, team, "k", HASH);

        assertThat(outcome)
                .isInstanceOfSatisfying(
                        Outcome.Proceed.class,
                        proceed -> assertThat(proceed.claim().claimedAt())
                                .isEqualTo(Instant.parse("2026-10-21T10:00:00.123456Z")));
    }

    @Test
    void aFinishedRequestIsReplayed() {
        Outcome.Proceed first = (Outcome.Proceed) service.begin(Location.CONTROL, team, "k", HASH);
        service.finish(first.claim(), RESPONSE);

        assertThat(service.begin(Location.CONTROL, team, "k", HASH.clone())).isEqualTo(new Outcome.Replay(RESPONSE));
    }

    @Test
    void anotherRequestWithTheKeyIsAConflict() {
        service.begin(Location.CONTROL, team, "k", HASH);

        assertThatThrownBy(() -> service.begin(Location.CONTROL, team, "k", new byte[] {9}))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                    assertThat(e.detail()).isEqualTo(IdempotencyService.REUSED_DETAIL);
                });
    }

    @Test
    void aRunningRequestIsAwaitedThenAConflict() {
        service.begin(Location.CONTROL, team, "k", HASH);
        Instant start = clock.instant();

        assertThatThrownBy(() -> service.begin(Location.CONTROL, team, "k", HASH))
                .isInstanceOfSatisfying(
                        ConflictException.class,
                        e -> assertThat(e.detail()).isEqualTo(IdempotencyService.IN_PROGRESS_DETAIL));
        assertThat(Duration.between(start, clock.instant())).isGreaterThanOrEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void aRunningRequestThatFinishesWhileWaitingIsReplayed() {
        Outcome.Proceed first = (Outcome.Proceed) service.begin(Location.CONTROL, team, "k", HASH);
        IdempotencyService waiting = new IdempotencyService(
                store,
                clock,
                new IdempotencyService.Settings(
                        Duration.ofHours(24), Duration.ofSeconds(60), Duration.ofSeconds(2), Duration.ofMillis(50)),
                duration -> {
                    clock.advance(duration);
                    service.finish(first.claim(), RESPONSE);
                });

        assertThat(waiting.begin(Location.CONTROL, team, "k", HASH)).isEqualTo(new Outcome.Replay(RESPONSE));
    }

    @Test
    void anAbandonedKeyCanBeUsedAgain() {
        Outcome.Proceed first = (Outcome.Proceed) service.begin(Location.CONTROL, team, "k", HASH);
        service.abandon(first.claim());

        assertThat(service.begin(Location.CONTROL, team, "k", new byte[] {7})).isInstanceOf(Outcome.Proceed.class);
    }

    @Test
    void staleAndExpiredRecordsAreTakenOver() {
        service.begin(Location.CONTROL, team, "stale", HASH);
        Outcome.Proceed done = (Outcome.Proceed) service.begin(Location.CONTROL, team, "expired", HASH);
        service.finish(done.claim(), RESPONSE);

        clock.advance(Duration.ofSeconds(61));
        assertThat(service.begin(Location.CONTROL, team, "stale", new byte[] {5}))
                .isInstanceOf(Outcome.Proceed.class);
        assertThat(service.begin(Location.CONTROL, team, "expired", HASH)).isInstanceOf(Outcome.Replay.class);

        clock.advance(Duration.ofHours(24));
        assertThat(service.begin(Location.CONTROL, team, "expired", new byte[] {6}))
                .isInstanceOf(Outcome.Proceed.class);
    }

    @Test
    void keysAreScopedByLocationAndTeam() {
        service.begin(Location.CONTROL, team, "k", HASH);

        assertThat(service.begin(Location.cell(dev.kairos.domain.shard.Shard.of(3)), team, "k", new byte[] {4}))
                .isInstanceOf(Outcome.Proceed.class);
        assertThat(service.begin(Location.CONTROL, TestIds.teamId(), "k", new byte[] {4}))
                .isInstanceOf(Outcome.Proceed.class);
    }

    @Test
    void settingsMustBePositive() {
        assertThatThrownBy(() -> new IdempotencyService.Settings(
                        Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
