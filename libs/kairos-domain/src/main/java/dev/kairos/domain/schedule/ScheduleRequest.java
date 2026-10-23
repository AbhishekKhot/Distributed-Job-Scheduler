package dev.kairos.domain.schedule;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The {@code schedule} object of a job or workflow request, as submitted (LLD section 3.6).
 *
 * <p>Only the fields of the chosen {@code type} are read: {@code at} for {@code ONE_TIME},
 * {@code expression} and {@code timezone} for {@code CRON}, {@code everySec} for {@code INTERVAL}.
 * Rejecting fields that do not belong to the type is left to the request binding.
 *
 * @param type {@code IMMEDIATE}, {@code ONE_TIME}, {@code CRON} or {@code INTERVAL}
 * @param at the RFC 3339 instant of a one-time schedule
 * @param expression the 5-field cron expression
 * @param timezone the IANA zone of a cron schedule
 * @param everySec the period of an interval schedule, in seconds
 */
public record ScheduleRequest(
        @Nullable String type,
        @Nullable String at,
        @Nullable String expression,
        @Nullable String timezone,
        @Nullable Long everySec) {

    /** Field error code for an unknown {@code type} (as for other enum fields in the LLD). */
    public static final String ENUM = "enum";

    /** Latest accepted one-time instant: the end of year 9999, the API's timestamp range. */
    public static final Instant MAX_AT = Instant.parse("9999-12-31T23:59:59Z");

    private static final int MAX_AT_LENGTH = 64;

    private static final String PREFIX = "schedule.";

    /**
     * Validates the request and builds the schedule.
     *
     * @param now the current time, for the one-time rule ({@code at >= now - 60s})
     * @return the schedule
     * @throws ValidationFailedException listing every invalid field, with paths such as
     *     {@code schedule.expression} and codes {@code required}, {@code enum}, {@code format},
     *     {@code range} and {@code invalid-cron}
     */
    public ScheduleSpec toSpec(Instant now) {
        Objects.requireNonNull(now, "now");
        List<FieldError> errors = new ArrayList<>();
        ScheduleSpec spec = null;
        if (type == null || type.isEmpty()) {
            errors.add(new FieldError(PREFIX + "type", FieldError.REQUIRED));
        } else {
            switch (type) {
                case "IMMEDIATE" -> spec = new ScheduleSpec.Immediate();
                case "ONE_TIME" -> spec = oneTime(now, errors);
                case "CRON" -> spec = cron(errors);
                case "INTERVAL" -> spec = interval(errors);
                default -> errors.add(new FieldError(PREFIX + "type", ENUM));
            }
        }
        if (spec == null || !errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
        return spec;
    }

    private ScheduleSpec.@Nullable OneTime oneTime(Instant now, List<FieldError> errors) {
        String field = PREFIX + "at";
        if (at == null || at.isEmpty()) {
            errors.add(new FieldError(field, FieldError.REQUIRED));
            return null;
        }
        if (at.length() > MAX_AT_LENGTH) {
            errors.add(new FieldError(field, FieldError.FORMAT));
            return null;
        }
        Instant instant;
        try {
            instant = DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(at, Instant::from);
        } catch (DateTimeException e) {
            errors.add(new FieldError(field, FieldError.FORMAT));
            return null;
        }
        if (instant.isAfter(MAX_AT)) {
            errors.add(new FieldError(field, FieldError.RANGE));
            return null;
        }
        return collect(() -> ScheduleSpec.OneTime.requested(instant, now), errors);
    }

    private ScheduleSpec.@Nullable Cron cron(List<FieldError> errors) {
        CronExpression parsed = null;
        if (expression == null || expression.isEmpty()) {
            errors.add(new FieldError(PREFIX + "expression", FieldError.REQUIRED));
        } else {
            parsed = collect(() -> CronExpression.parse(expression), errors);
        }
        ZoneId zone = null;
        if (timezone == null || timezone.isEmpty()) {
            errors.add(new FieldError(IanaZones.FIELD, FieldError.REQUIRED));
        } else {
            zone = collect(() -> IanaZones.parse(timezone), errors);
        }
        return parsed == null || zone == null ? null : new ScheduleSpec.Cron(parsed, zone);
    }

    private ScheduleSpec.@Nullable Interval interval(List<FieldError> errors) {
        if (everySec == null) {
            errors.add(new FieldError(PREFIX + "everySec", FieldError.REQUIRED));
            return null;
        }
        long seconds = everySec;
        if (seconds < 1 || seconds > ScheduleSpec.MAX_INTERVAL.toSeconds()) {
            errors.add(new FieldError(PREFIX + "everySec", FieldError.RANGE));
            return null;
        }
        return new ScheduleSpec.Interval(Duration.ofSeconds(seconds));
    }

    private static <T> @Nullable T collect(Step<T> step, List<FieldError> errors) {
        try {
            return step.run();
        } catch (ValidationFailedException e) {
            errors.addAll(e.fieldErrors());
            return null;
        }
    }

    @FunctionalInterface
    private interface Step<T> {
        T run();
    }
}
