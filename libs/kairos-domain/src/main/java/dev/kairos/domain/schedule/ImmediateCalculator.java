package dev.kairos.domain.schedule;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** {@code IMMEDIATE}: the single run is created with the job, so the schedule never fires. */
final class ImmediateCalculator implements ScheduleCalculator {

    static final ImmediateCalculator INSTANCE = new ImmediateCalculator();

    private ImmediateCalculator() {}

    @Override
    public Optional<Fire> first(Instant now) {
        return Optional.empty();
    }

    @Override
    public Optional<Fire> nextFire(Instant after, @Nullable LocalDateTime lastFiredLocal) {
        return Optional.empty();
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
