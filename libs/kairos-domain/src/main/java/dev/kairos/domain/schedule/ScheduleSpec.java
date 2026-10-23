package dev.kairos.domain.schedule;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.model.ScheduleKind;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/**
 * When a job or workflow runs ({@code schedule} in job and workflow specs, LLD section 3.6).
 *
 * <p>The records check the rules that hold for every stored schedule: a valid 5-field cron
 * expression, an IANA region time zone, and an interval of at least one second. The rule that a
 * one-time instant is no more than 60 seconds in the past applies only when a schedule is
 * submitted, so it lives in {@link OneTime#requested(Instant, Instant)} and
 * {@link ScheduleRequest#toSpec(Instant)}.
 */
public sealed interface ScheduleSpec
        permits ScheduleSpec.Immediate, ScheduleSpec.OneTime, ScheduleSpec.Cron, ScheduleSpec.Interval {

    /** How far in the past a submitted one-time instant may be. */
    Duration ONE_TIME_GRACE = Duration.ofSeconds(60);

    /** Shortest interval. */
    Duration MIN_INTERVAL = Duration.ofSeconds(1);

    /** Longest interval: 366 days, so interval arithmetic can never overflow. */
    Duration MAX_INTERVAL = Duration.ofDays(366);

    /**
     * Returns the stored kind ({@code schedules.kind}).
     *
     * @return the kind, or empty for {@link Immediate}, which has no schedule row
     */
    Optional<ScheduleKind> kind();

    /** Runs once, when the job is created. No schedule row is stored. */
    record Immediate() implements ScheduleSpec {

        @Override
        public Optional<ScheduleKind> kind() {
            return Optional.empty();
        }
    }

    /**
     * Fires once at a fixed instant.
     *
     * @param at the instant
     */
    record OneTime(Instant at) implements ScheduleSpec {

        /**
         * Validates the components.
         *
         * @throws NullPointerException if {@code at} is null
         */
        public OneTime {
            Objects.requireNonNull(at, "at");
        }

        /**
         * Creates a one-time schedule submitted at {@code now}, which may be at most
         * {@link #ONE_TIME_GRACE} in the past.
         *
         * @param at the requested instant
         * @param now the current time
         * @return the schedule
         * @throws ValidationFailedException with code {@link FieldError#RANGE} on
         *     {@code schedule.at} if {@code at} is before {@code now - 60s}
         */
        public static OneTime requested(Instant at, Instant now) {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(now, "now");
            if (at.isBefore(now.minus(ONE_TIME_GRACE))) {
                throw ValidationFailedException.of("schedule.at", FieldError.RANGE);
            }
            return new OneTime(at);
        }

        @Override
        public Optional<ScheduleKind> kind() {
            return Optional.of(ScheduleKind.ONE_TIME);
        }
    }

    /**
     * Fires on a cron expression evaluated in a time zone.
     *
     * @param expression the 5-field expression
     * @param zone an IANA region zone, for example {@code Asia/Kolkata}
     */
    record Cron(CronExpression expression, ZoneId zone) implements ScheduleSpec {

        /**
         * Validates the components.
         *
         * @throws ValidationFailedException with code {@link FieldError#FORMAT} on
         *     {@code schedule.timezone} if the zone is not an IANA region id (fixed offsets such
         *     as {@code +05:30} are rejected)
         * @throws NullPointerException if a component is null
         */
        public Cron {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(zone, "zone");
            if (!IanaZones.isIanaZone(zone.getId())) {
                throw ValidationFailedException.of(IanaZones.FIELD, FieldError.FORMAT);
            }
        }

        /**
         * Parses a cron schedule from its stored or submitted text.
         *
         * @param expression the expression text
         * @param timezone the IANA zone id
         * @return the schedule
         * @throws ValidationFailedException on {@code schedule.expression} or
         *     {@code schedule.timezone}
         */
        public static Cron of(String expression, String timezone) {
            return new Cron(CronExpression.parse(expression), IanaZones.parse(timezone));
        }

        @Override
        public Optional<ScheduleKind> kind() {
            return Optional.of(ScheduleKind.CRON);
        }
    }

    /**
     * Fires every {@code every}.
     *
     * @param every the period, 1 second to 366 days, in whole milliseconds
     */
    record Interval(Duration every) implements ScheduleSpec {

        /**
         * Validates the components.
         *
         * @throws ValidationFailedException with code {@link FieldError#RANGE} on
         *     {@code schedule.everySec} if the period is outside 1 second to 366 days or is not a
         *     whole number of milliseconds
         * @throws NullPointerException if {@code every} is null
         */
        public Interval {
            Objects.requireNonNull(every, "every");
            if (every.compareTo(MIN_INTERVAL) < 0
                    || every.compareTo(MAX_INTERVAL) > 0
                    || every.toNanosPart() % 1_000_000 != 0) {
                throw ValidationFailedException.of("schedule.everySec", FieldError.RANGE);
            }
        }

        @Override
        public Optional<ScheduleKind> kind() {
            return Optional.of(ScheduleKind.INTERVAL);
        }
    }
}
