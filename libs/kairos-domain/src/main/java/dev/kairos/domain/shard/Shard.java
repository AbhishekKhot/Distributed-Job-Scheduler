package dev.kairos.domain.shard;

/**
 * One of the 256 logical shards that partition jobs, workflows and their runs.
 *
 * <p>A shard is fixed for the lifetime of a job or workflow (it is derived from the team and the
 * immutable name by {@link ShardKey}) and is embedded in the low 8 bits of every sharded id, so
 * any id routes to its cell database without a lookup. Instances are immutable and thread-safe.
 *
 * @param value the shard number, 0 to 255
 */
public record Shard(int value) implements Comparable<Shard> {

    /** Number of shards. Fixed forever: changing it would move every existing job. */
    public static final int COUNT = 256;

    /** Highest valid shard number. */
    public static final int MAX = COUNT - 1;

    /**
     * Validates the shard number.
     *
     * @throws IllegalArgumentException if {@code value} is outside 0 to 255
     */
    public Shard {
        if (value < 0 || value > MAX) {
            throw new IllegalArgumentException("Shard must be between 0 and " + MAX);
        }
    }

    /**
     * Returns the shard with the given number.
     *
     * @param value the shard number, 0 to 255
     * @return the shard
     * @throws IllegalArgumentException if {@code value} is outside 0 to 255
     */
    public static Shard of(int value) {
        return new Shard(value);
    }

    @Override
    public int compareTo(Shard other) {
        return Integer.compare(value, other.value);
    }
}
