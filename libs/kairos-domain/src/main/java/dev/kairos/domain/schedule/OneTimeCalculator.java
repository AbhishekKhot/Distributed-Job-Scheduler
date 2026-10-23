package dev.kairos.domain.schedule;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** {@code ONE_TIME}: a single occurrence at a fixed instant. */
final class OneTimeCalculator implements ScheduleCalculator {

    private final Fire fire;

    OneTimeCalculator(Instant at) {
        this.fire = new Fire(Objects.requireNonNull(at, "at"), LocalDateTime.ofInstant(at, ZoneOffset.UTC));
    }

    @Override
    public Optional<Fire> first(Instant now) {
        return Optional.of(fire);
    }

    @Override
    public Optional<Fire> nextFire(Instant after, @Nullable LocalDateTime lastFiredLocal) {
        Objects.requireNonNull(after, "after");
        return fire.at().isAfter(after) && lastFiredLocal == null ? Optional.of(fire) : Optional.empty();
    }

    @Override
    public Fire fireAt(Instant at) {
        return new Fire(at, LocalDateTime.ofInstant(at, ZoneOffset.UTC));
    }

    @Override
    public Optional<Duration> period() {
        return Optional.empty();
    }
}
