package dev.kairos.domain.retry;

import java.util.random.RandomGenerator;

/** Random generators that always draw the same fraction, so jitter is exact in tests. */
final class Draws {

    private static final double TWO_POW_53 = 0x1.0p53;

    private Draws() {}

    /**
     * Returns a generator whose {@code nextDouble()} always returns (almost exactly) {@code fraction}.
     *
     * @param fraction in {@code [0, 1)}
     */
    static RandomGenerator always(double fraction) {
        // RandomGenerator.nextDouble() is (nextLong() >>> 11) * 2^-53, so put the 53-bit value back.
        long bits = ((long) (fraction * TWO_POW_53)) << 11;
        return () -> bits;
    }

    /** Returns a generator that draws the smallest fraction, 0. */
    static RandomGenerator lowest() {
        return always(0.0);
    }

    /** Returns a generator that draws the largest fraction below 1. */
    static RandomGenerator highest() {
        return () -> -1L;
    }
}
