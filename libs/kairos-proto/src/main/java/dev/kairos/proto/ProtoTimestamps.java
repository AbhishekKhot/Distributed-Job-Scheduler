package dev.kairos.proto;

import com.google.protobuf.Timestamp;
import java.time.Instant;

/**
 * Converts between {@link Instant} and {@code google.protobuf.Timestamp}.
 *
 * <p>Timestamps are UTC instants with nanosecond precision. Decoding validates the range defined by
 * the well-known type (0001-01-01T00:00:00Z to 9999-12-31T23:59:59.999999999Z), so a corrupt or
 * hostile message fails here instead of producing an absurd time further down. This class is
 * stateless and thread-safe.
 */
public final class ProtoTimestamps {

    /** Seconds of 0001-01-01T00:00:00Z, the earliest valid timestamp. */
    static final long MIN_SECONDS = -62_135_596_800L;

    /** Seconds of 9999-12-31T23:59:59Z, the latest valid timestamp. */
    static final long MAX_SECONDS = 253_402_300_799L;

    private static final int MAX_NANOS = 999_999_999;

    private ProtoTimestamps() {}

    /**
     * Encodes an instant.
     *
     * @param instant the instant to encode
     * @return the timestamp
     * @throws IllegalArgumentException if the instant is outside the valid timestamp range
     */
    public static Timestamp toTimestamp(Instant instant) {
        checkRange(instant.getEpochSecond(), instant.getNano());
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    /**
     * Decodes a timestamp.
     *
     * @param timestamp the timestamp to decode
     * @return the instant
     * @throws IllegalArgumentException if the seconds or nanoseconds are outside the valid range
     */
    public static Instant toInstant(Timestamp timestamp) {
        checkRange(timestamp.getSeconds(), timestamp.getNanos());
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    private static void checkRange(long seconds, int nanos) {
        if (seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("Timestamp seconds are outside 0001-01-01 to 9999-12-31");
        }
        if (nanos < 0 || nanos > MAX_NANOS) {
            throw new IllegalArgumentException("Timestamp nanoseconds must be between 0 and " + MAX_NANOS);
        }
    }
}
