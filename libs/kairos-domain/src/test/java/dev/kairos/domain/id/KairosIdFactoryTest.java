package dev.kairos.domain.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.kairos.domain.shard.Shard;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class KairosIdFactoryTest {

    private static final long START = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli();

    private final SteppingClock clock = new SteppingClock(START);
    private final KairosIdFactory factory = new KairosIdFactory(clock, new SecureRandom());

    @Property
    void shardRoundTrips(@ForAll @IntRange(min = 0, max = Shard.MAX) int shard) {
        UUID id = factory.newId(Shard.of(shard));

        assertThat(KairosId.shardOf(id)).isEqualTo(Shard.of(shard));
        assertThat(KairosId.isKairosId(id)).isTrue();
    }

    @Property
    void idsSortByCreationTime(@ForAll @Size(min = 2, max = 200) List<@LongRange(min = -5, max = 1000) Long> steps) {
        // Steps include zero (same millisecond) and negative values (the clock moving backwards).
        List<UUID> ids = new ArrayList<>();
        for (long step : steps) {
            clock.advance(step);
            ids.add(factory.newId(Shard.of(Math.floorMod(step, Shard.COUNT))));
        }

        for (int i = 1; i < ids.size(); i++) {
            assertThat(ids.get(i)).isGreaterThan(ids.get(i - 1));
            assertThat(unsignedCompare(ids.get(i), ids.get(i - 1))).isPositive();
        }
    }

    @Test
    void embedsTheClockTime() {
        UUID id = factory.newId();

        assertThat(KairosId.timestampOf(id)).isEqualTo(Instant.ofEpochMilli(START));
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void staysOrderedAndUniqueInALargeBurstWithinOneMillisecond() {
        int count = 20_000;
        UUID previous = factory.newId();
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < count; i++) {
            UUID next = factory.newId(Shard.of(i % Shard.COUNT));
            assertThat(next).isGreaterThan(previous);
            assertThat(seen.add(next)).isTrue();
            previous = next;
        }
        // At most 4096 ids per logical millisecond, so the timestamp moved forward a little.
        Instant last = KairosId.timestampOf(previous);
        assertThat(last).isAfter(Instant.ofEpochMilli(START));
        assertThat(last).isBefore(Instant.ofEpochMilli(START + count / 2048 + 2));
    }

    @Test
    void resumesRealTimeAfterTheClockCatchesUp() {
        factory.newId();
        clock.advance(-10_000);
        UUID duringRollback = factory.newId();
        clock.set(START + 60_000);
        UUID afterRecovery = factory.newId();

        assertThat(KairosId.timestampOf(duringRollback)).isEqualTo(Instant.ofEpochMilli(START));
        assertThat(KairosId.timestampOf(afterRecovery)).isEqualTo(Instant.ofEpochMilli(START + 60_000));
    }

    @Test
    void createsTypedIds() {
        Shard shard = Shard.of(201);

        assertThat(factory.newJobId(shard).shard()).isEqualTo(shard);
        assertThat(factory.newWorkflowId(shard).shard()).isEqualTo(shard);
        assertThat(factory.newRunId(shard).shard()).isEqualTo(shard);
        assertThat(factory.newWorkflowRunId(shard).shard()).isEqualTo(shard);
        assertThat(factory.newDlqId(shard).shard()).isEqualTo(shard);
        assertThat(KairosId.isKairosId(factory.newTeamId().value())).isTrue();
        assertThat(KairosId.isKairosId(factory.newUserId().value())).isTrue();
        assertThat(KairosId.isKairosId(factory.newEndpointId().value())).isTrue();
    }

    @Test
    void keepsRandomBitsAcrossIds() {
        UUID first = factory.newId(Shard.of(7));
        UUID second = factory.newId(Shard.of(7));

        assertThat(first.getLeastSignificantBits() >>> 8).isNotEqualTo(second.getLeastSignificantBits() >>> 8);
    }

    @Test
    void refusesClocksOutsideTheTimestampRange() {
        clock.set(-1);
        KairosIdFactory beforeEpoch = new KairosIdFactory(clock, new SecureRandom());
        assertThatIllegalStateException().isThrownBy(beforeEpoch::newId);

        clock.set(1L << 48);
        KairosIdFactory tooLate = new KairosIdFactory(clock, new SecureRandom());
        assertThatIllegalStateException().isThrownBy(tooLate::newId);
    }

    /** Byte-wise unsigned comparison, the order PostgreSQL uses for {@code uuid}. */
    private static int unsignedCompare(UUID left, UUID right) {
        return Arrays.compareUnsigned(bytes(left), bytes(right));
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }
}
