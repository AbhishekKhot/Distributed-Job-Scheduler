package dev.kairos.domain.schedule;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import dev.kairos.domain.id.ShardedId;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * The fixed per-schedule offset added to each occurrence's due time ({@code schedules.spread_ms}),
 * so that the many schedules due at the top of a minute do not all dispatch in the same instant.
 *
 * <p>The offset is {@code floorMod(murmur3_32_fixed(seed 0x5ca1ab1e, uuid bytes), bound)}
 * milliseconds, where {@code bound = min(60s, period / 10)} and the UUID bytes are the id's most
 * then least significant 64 bits, big-endian. It is therefore in {@code [0, bound)}, the same for
 * every caller, and independent of the shard (which uses a different hash input). Schedules that
 * fire at most once, and periods under 10 ms, get no spread.
 */
public final class Spread {

    /** Upper bound (exclusive) of any spread. */
    public static final Duration MAX = Duration.ofSeconds(60);

    private static final int SEED = 0x5ca1ab1e;

    private static final HashFunction HASH = Hashing.murmur3_32_fixed(SEED);

    private Spread() {}

    /**
     * Returns the spread of a schedule.
     *
     * @param scheduleId the schedule id (the job or workflow id)
     * @param calculator the schedule's calculator, for its period
     * @return the offset, in whole milliseconds, in {@code [0, min(60s, period / 10))}
     */
    public static Duration of(ShardedId scheduleId, ScheduleCalculator calculator) {
        Objects.requireNonNull(scheduleId, "scheduleId");
        Objects.requireNonNull(calculator, "calculator");
        return calculator.period().map(p -> of(scheduleId.value(), p)).orElse(Duration.ZERO);
    }

    /**
     * Returns the spread for an id and a period.
     *
     * @param id the schedule id
     * @param period the shortest gap between occurrences
     * @return the offset, in whole milliseconds, in {@code [0, min(60s, period / 10))}
     */
    public static Duration of(UUID id, Duration period) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(period, "period");
        long boundMs = Math.min(MAX.toMillis(), period.dividedBy(10).toMillis());
        if (boundMs <= 1) {
            return Duration.ZERO;
        }
        byte[] bytes = ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
        long hash = Integer.toUnsignedLong(HASH.hashBytes(bytes).asInt());
        return Duration.ofMillis(Math.floorMod(hash, boundMs));
    }
}
