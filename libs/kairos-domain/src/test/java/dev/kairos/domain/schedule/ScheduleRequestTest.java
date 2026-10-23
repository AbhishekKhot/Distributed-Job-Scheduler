package dev.kairos.domain.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.model.ScheduleKind;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScheduleRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-14T09:30:00Z");

    private static ScheduleRequest request(
            @Nullable String type,
            @Nullable String at,
            @Nullable String expression,
            @Nullable String timezone,
            @Nullable Long everySec) {
        return new ScheduleRequest(type, at, expression, timezone, everySec);
    }

    private static void assertInvalid(ScheduleRequest request, FieldError... errors) {
        assertThatThrownBy(() -> request.toSpec(NOW))
                .isInstanceOfSatisfying(
                        ValidationFailedException.class,
                        e -> assertThat(e.fieldErrors()).containsExactly(errors));
    }

    @Test
    void immediate() {
        ScheduleSpec spec = request("IMMEDIATE", null, null, null, null).toSpec(NOW);

        assertThat(spec).isEqualTo(new ScheduleSpec.Immediate());
        assertThat(spec.kind()).isEmpty();
    }

    @Test
    void oneTimeAllowsUpToSixtySecondsInThePast() {
        assertThat(request("ONE_TIME", "2026-10-14T09:29:00Z", null, null, null).toSpec(NOW))
                .isEqualTo(new ScheduleSpec.OneTime(Instant.parse("2026-10-14T09:29:00Z")));
        assertThat(request("ONE_TIME", "2026-10-14T15:00:00+05:30", null, null, null)
                        .toSpec(NOW))
                .isEqualTo(new ScheduleSpec.OneTime(Instant.parse("2026-10-14T09:30:00Z")));
        assertThat(new ScheduleSpec.OneTime(NOW).kind()).contains(ScheduleKind.ONE_TIME);
        assertInvalid(
                request("ONE_TIME", "2026-10-14T09:28:59Z", null, null, null),
                new FieldError("schedule.at", FieldError.RANGE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tomorrow", "2026-10-14", "2026-10-14T09:30:00", "2026-13-01T00:00:00Z"})
    void oneTimeNeedsAnRfc3339Instant(String at) {
        assertInvalid(request("ONE_TIME", at, null, null, null), new FieldError("schedule.at", FieldError.FORMAT));
    }

    @Test
    void oneTimeLimits() {
        assertInvalid(request("ONE_TIME", null, null, null, null), new FieldError("schedule.at", FieldError.REQUIRED));
        assertInvalid(
                request(
                        "ONE_TIME",
                        "2026-10-14T09:30:00.000000000000000000000000000000000000000000000000000000Z",
                        null,
                        null,
                        null),
                new FieldError("schedule.at", FieldError.FORMAT));
        assertInvalid(
                request("ONE_TIME", "+10000-01-01T00:00:00Z", null, null, null),
                new FieldError("schedule.at", FieldError.RANGE));
    }

    @Test
    void cron() {
        ScheduleSpec spec =
                request("CRON", null, "0 2 * * *", "Asia/Kolkata", null).toSpec(NOW);

        assertThat(spec).isEqualTo(new ScheduleSpec.Cron(CronExpression.parse("0 2 * * *"), ZoneId.of("Asia/Kolkata")));
        assertThat(spec.kind()).contains(ScheduleKind.CRON);
        assertThat(ScheduleSpec.Cron.of("0 2 * * *", "Asia/Kolkata")).isEqualTo(spec);
    }

    @Test
    void cronReportsEveryInvalidField() {
        assertInvalid(
                request("CRON", null, null, null, null),
                new FieldError("schedule.expression", FieldError.REQUIRED),
                new FieldError("schedule.timezone", FieldError.REQUIRED));
        assertInvalid(
                request("CRON", null, "0 2 * *", "Asia/Calcutta2", null),
                new FieldError("schedule.expression", CronExpression.INVALID_CRON),
                new FieldError("schedule.timezone", FieldError.FORMAT));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "+05:30",
                "UTC+05:30",
                "GMT+1",
                "Z",
                "IST",
                "Mars/Olympus_Mons",
                "asia/kolkata",
                "Europe/../London"
            })
    void cronAcceptsIanaRegionZonesOnly(String timezone) {
        assertInvalid(
                request("CRON", null, "0 2 * * *", timezone, null),
                new FieldError("schedule.timezone", FieldError.FORMAT));
    }

    @Test
    void cronRecordRejectsOffsetZones() {
        CronExpression expression = CronExpression.parse("0 2 * * *");

        assertThatThrownBy(() -> new ScheduleSpec.Cron(expression, ZoneOffset.ofHoursMinutes(5, 30)))
                .isInstanceOf(ValidationFailedException.class);
        assertThat(new ScheduleSpec.Cron(expression, ZoneId.of("UTC")).zone()).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void interval() {
        ScheduleSpec spec = request("INTERVAL", null, null, null, 3_600L).toSpec(NOW);

        assertThat(spec).isEqualTo(new ScheduleSpec.Interval(Duration.ofHours(1)));
        assertThat(spec.kind()).contains(ScheduleKind.INTERVAL);
        assertThat(request("INTERVAL", null, null, null, 1L).toSpec(NOW))
                .isEqualTo(new ScheduleSpec.Interval(Duration.ofSeconds(1)));
        assertThat(request("INTERVAL", null, null, null, 31_622_400L).toSpec(NOW))
                .isEqualTo(new ScheduleSpec.Interval(Duration.ofDays(366)));
    }

    @Test
    void intervalLimits() {
        FieldError range = new FieldError("schedule.everySec", FieldError.RANGE);
        assertInvalid(
                request("INTERVAL", null, null, null, null), new FieldError("schedule.everySec", FieldError.REQUIRED));
        assertInvalid(request("INTERVAL", null, null, null, 0L), range);
        assertInvalid(request("INTERVAL", null, null, null, -5L), range);
        assertInvalid(request("INTERVAL", null, null, null, 31_622_401L), range);
        assertInvalid(request("INTERVAL", null, null, null, Long.MAX_VALUE), range);
        assertThatThrownBy(() -> new ScheduleSpec.Interval(Duration.ofMillis(999)))
                .isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> new ScheduleSpec.Interval(Duration.ofSeconds(1).plusNanos(1)))
                .isInstanceOf(ValidationFailedException.class);
        assertThat(new ScheduleSpec.Interval(Duration.ofMillis(1_500)).every()).isEqualTo(Duration.ofMillis(1_500));
    }

    @Test
    void typeIsRequiredAndMustBeKnown() {
        assertInvalid(request(null, null, null, null, null), new FieldError("schedule.type", FieldError.REQUIRED));
        assertInvalid(request("", null, null, null, null), new FieldError("schedule.type", FieldError.REQUIRED));
        assertInvalid(request("cron", null, null, null, null), new FieldError("schedule.type", ScheduleRequest.ENUM));
    }

    @Test
    void fieldsOfOtherTypesAreIgnored() {
        assertThat(request("INTERVAL", "garbage", "garbage", "garbage", 60L).toSpec(NOW))
                .isEqualTo(new ScheduleSpec.Interval(Duration.ofMinutes(1)));
    }

    @Test
    void nullsAreRejected() {
        assertThatNullPointerException().isThrownBy(() -> new ScheduleSpec.OneTime(null));
        assertThatNullPointerException().isThrownBy(() -> ScheduleSpec.OneTime.requested(NOW, null));
        assertThatNullPointerException()
                .isThrownBy(() -> request("IMMEDIATE", null, null, null, null).toSpec(null));
    }
}
