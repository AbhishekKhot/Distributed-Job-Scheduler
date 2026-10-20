package dev.kairos.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Clock} that only moves when a test moves it.
 *
 * <p>Inject it wherever production code takes a {@code Clock}, then call {@link #advance(Duration)} or
 * {@link #set(Instant)} to step time forward (or anywhere else) without sleeping. Clocks derived with
 * {@link #withZone(ZoneId)} share the same instant, so advancing one advances all of them.
 *
 * <p>Thread-safe: the instant is held in an atomic reference, so a test can move time while code under test reads it
 * from other threads.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> instant;
    private final ZoneId zone;

    private MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    /**
     * Creates a clock that reads {@code start} in UTC until it is moved.
     *
     * @param start the initial instant
     * @return a new clock
     */
    public static MutableClock at(Instant start) {
        return at(start, ZoneOffset.UTC);
    }

    /**
     * Creates a clock that reads {@code start} in the given zone until it is moved.
     *
     * @param start the initial instant
     * @param zone the zone reported by {@link #getZone()}
     * @return a new clock
     */
    public static MutableClock at(Instant start, ZoneId zone) {
        return new MutableClock(new AtomicReference<>(start), zone);
    }

    /**
     * Moves the clock forward.
     *
     * @param amount how far to move; zero is allowed
     * @return the new instant
     * @throws IllegalArgumentException if {@code amount} is negative; use {@link #set(Instant)} to go back in time
     */
    public Instant advance(Duration amount) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("Use set(Instant) to move the clock backwards");
        }
        return instant.updateAndGet(current -> current.plus(amount));
    }

    /**
     * Sets the clock to an instant, earlier or later than the current one.
     *
     * @param newInstant the instant the clock reads from now on
     */
    public void set(Instant newInstant) {
        instant.set(newInstant);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    /**
     * Returns a clock in another zone that shares this clock's instant.
     *
     * @param newZone the zone of the returned clock
     * @return a clock that moves together with this one
     */
    @Override
    public MutableClock withZone(ZoneId newZone) {
        return newZone.equals(zone) ? this : new MutableClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant.get();
    }

    @Override
    public String toString() {
        return "MutableClock[" + instant.get() + "," + zone + "]";
    }
}
