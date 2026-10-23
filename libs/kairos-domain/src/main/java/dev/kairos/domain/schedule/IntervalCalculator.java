package dev.kairos.domain.schedule;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code INTERVAL}: occurrences at {@code anchor + k * every} for every integer {@code k}.
 *
 * <p>Interval schedules ignore time zones and daylight saving: the period is elapsed time.
 */
final class IntervalCalculator implements ScheduleCalculator {

    private final Duration every;
    private final Instant anchor;

    IntervalCalculator(Duration every, Instant anchor) {
        this.every = Objects.requireNonNull(every, "every");
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        if (every.isNegative() || every.isZero()) {
            throw new IllegalArgumentException("The interval must be positive");
        }
    }

    @Override
    public Optional<Fire> first(Instant now) {
        return nextFire(now, null);
    }

    @Override
    public Optional<Fire> nextFire(Instant after, @Nullable LocalDateTime lastFiredLocal) {
        Objects.requireNonNull(after, "after");
        Instant next;
        if (after.isBefore(anchor)) {
            // Occurrences before the anchor are never produced: the anchor is the first one.
            next = anchor;
        } else {
            long periods = Duration.between(anchor, after).dividedBy(every);
            next = anchor.plus(every.multipliedBy(periods + 1));
        }
        return Optional.of(fireAt(next));
    }

    @Override
    public Fire fireAt(Instant at) {
        return new Fire(at, LocalDateTime.ofInstant(at, ZoneOffset.UTC));
    }

    @Override
    public Optional<Duration> period() {
        return Optional.of(every);
    }
}
