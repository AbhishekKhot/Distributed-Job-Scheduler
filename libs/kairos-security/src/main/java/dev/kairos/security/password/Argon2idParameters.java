package dev.kairos.security.password;

/**
 * Cost parameters of an Argon2id hash (RFC 9106).
 *
 * <p>{@link #DEFAULT} is the OWASP baseline the LLD prescribes: 19 MiB of memory, 2 iterations, parallelism 1, a
 * 16-byte salt and a 32-byte hash. Parameters read back from a stored hash are bounded by {@link #requireWithinLimits()},
 * so a corrupted or planted hash cannot make a login allocate gigabytes or loop for minutes.
 *
 * @param memoryKib memory cost in KiB
 * @param iterations number of passes over the memory
 * @param parallelism number of lanes
 * @param saltLength salt length in bytes
 * @param hashLength hash (tag) length in bytes
 */
public record Argon2idParameters(int memoryKib, int iterations, int parallelism, int saltLength, int hashLength) {

    /** The parameters new hashes are written with: m = 19456 KiB, t = 2, p = 1, 16-byte salt, 32-byte hash. */
    public static final Argon2idParameters DEFAULT = new Argon2idParameters(19 * 1024, 2, 1, 16, 32);

    /** The largest memory cost accepted from a stored hash: 256 MiB. */
    static final int MAX_MEMORY_KIB = 256 * 1024;

    /** The largest iteration count accepted from a stored hash. */
    static final int MAX_ITERATIONS = 16;

    /** The largest parallelism accepted from a stored hash. */
    static final int MAX_PARALLELISM = 16;

    /**
     * Validates the parameters against the Argon2 minimums.
     *
     * @throws IllegalArgumentException if a parameter is below the Argon2 minimum (8 KiB per lane, 1 iteration,
     *     1 lane, 8-byte salt, 16-byte hash)
     */
    public Argon2idParameters {
        if (parallelism < 1 || iterations < 1 || memoryKib < 8 * parallelism || saltLength < 8 || hashLength < 16) {
            throw new IllegalArgumentException("Argon2id parameters are below the minimums of RFC 9106");
        }
    }

    /**
     * Checks that parameters read from a stored hash stay within what this service is willing to compute.
     *
     * @return these parameters
     * @throws IllegalArgumentException if a cost exceeds the limits
     */
    Argon2idParameters requireWithinLimits() {
        if (memoryKib > MAX_MEMORY_KIB
                || iterations > MAX_ITERATIONS
                || parallelism > MAX_PARALLELISM
                || saltLength > 64
                || hashLength > 64) {
            throw new IllegalArgumentException("Argon2id parameters exceed the accepted limits");
        }
        return this;
    }
}
