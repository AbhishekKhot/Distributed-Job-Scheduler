package dev.kairos.watcher.fire;

import dev.kairos.domain.model.DstPolicy;
import dev.kairos.domain.model.MisfirePolicy;
import dev.kairos.domain.model.ScheduleKind;
import dev.kairos.domain.model.TargetType;
import dev.kairos.domain.schedule.FirePlan;
import dev.kairos.domain.schedule.MisfireHandler;
import dev.kairos.domain.schedule.ScheduleCalculator;
import dev.kairos.domain.schedule.ScheduleSpec;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Persisted schedule and the domain calculation needed to advance it. */
public record FireSchedule(
        UUID id,
        UUID teamId,
        TargetType targetType,
        int shard,
        ScheduleKind kind,
        Instant nextFireAt,
        @Nullable String cron,
        String timezone,
        @Nullable Long intervalMillis,
        int spreadMillis,
        DstPolicy dstPolicy,
        MisfirePolicy misfirePolicy,
        int maxCatchup,
        int definitionVersion,
        int priority,
        @Nullable LocalDateTime lastFiredLocal) {

    /** Computes occurrences and the next durable schedule position. */
    public FirePlan plan(MisfireHandler handler, Instant now, Duration lookAhead) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lookAhead, "lookAhead");
        ScheduleSpec spec = switch (kind) {
            case ONE_TIME -> new ScheduleSpec.OneTime(nextFireAt);
            case CRON -> ScheduleSpec.Cron.of(Objects.requireNonNull(cron), timezone);
            case INTERVAL -> new ScheduleSpec.Interval(Duration.ofMillis(Objects.requireNonNull(intervalMillis)));
        };
        ScheduleCalculator calculator = ScheduleCalculator.of(spec, dstPolicy, nextFireAt);
        return handler.plan(
                calculator, nextFireAt, lastFiredLocal, now, now.plus(lookAhead), misfirePolicy, maxCatchup);
    }
}
