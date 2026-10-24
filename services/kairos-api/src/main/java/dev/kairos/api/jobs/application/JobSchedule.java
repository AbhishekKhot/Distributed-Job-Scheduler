package dev.kairos.api.jobs.application;

import dev.kairos.domain.model.DstPolicy;
import dev.kairos.domain.model.MisfirePolicy;
import dev.kairos.domain.model.ScheduleKind;
import dev.kairos.domain.schedule.Fire;
import dev.kairos.domain.schedule.ScheduleCalculator;
import dev.kairos.domain.schedule.ScheduleSpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Cell row values derived from one validated job definition. */
public record JobSchedule(
        @Nullable ScheduleKind kind,
        @Nullable Instant nextFireAt,
        @Nullable String cronExpression,
        String timezone,
        @Nullable Long intervalMs,
        int spreadMs,
        int dstPolicy,
        MisfirePolicy misfirePolicy,
        int maxCatchup) {

    /** Computes the first fire and storage representation from the schedule and job id. */
    public static JobSchedule from(JobSpec job, Instant now) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(now, "now");
        if (job.schedule() instanceof ScheduleSpec.Immediate) {
            return new JobSchedule(
                    null, now, null, "UTC", null, 0, DstPolicy.DEFAULT.code(), job.misfirePolicy(), job.maxCatchup());
        }
        ScheduleCalculator calculator = ScheduleCalculator.of(job.schedule(), DstPolicy.DEFAULT, now);
        Duration spread = Duration.ofSeconds(job.spreadSec());
        if (spread.isNegative() || spread.compareTo(Duration.ofSeconds(60)) > 0) {
            throw dev.kairos.domain.error.ValidationFailedException.of("schedule.spreadSec", "range");
        }
        calculator.period().ifPresent(period -> {
            if (spread.multipliedBy(10).compareTo(period) >= 0 && !spread.isZero()) {
                throw dev.kairos.domain.error.ValidationFailedException.of("schedule.spreadSec", "range");
            }
        });
        Fire first = calculator.first(now).orElse(null);
        ScheduleKind kind = job.schedule().kind().orElseThrow();
        String cron = job.schedule() instanceof ScheduleSpec.Cron value
                ? value.expression().text()
                : null;
        String timezone =
                job.schedule() instanceof ScheduleSpec.Cron value ? value.zone().getId() : ZoneOffset.UTC.getId();
        Long interval = job.schedule() instanceof ScheduleSpec.Interval value
                ? value.every().toMillis()
                : null;
        return new JobSchedule(
                kind,
                first == null ? null : first.at(),
                cron,
                timezone,
                interval,
                Math.toIntExact(spread.toMillis()),
                DstPolicy.DEFAULT.code(),
                job.misfirePolicy(),
                job.maxCatchup());
    }
}
