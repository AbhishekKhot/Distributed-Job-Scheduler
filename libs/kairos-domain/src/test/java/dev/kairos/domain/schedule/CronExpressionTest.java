package dev.kairos.domain.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CronExpressionTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0 2 * * *",
                "*/15 9-17 * * MON-FRI",
                "0 0 1 JAN *",
                "5,35 */2 1-15 * 0",
                "0 0 29 2 *",
                "59 23 31 12 7"
            })
    void acceptsFiveFieldUnixExpressions(String text) {
        assertThat(CronExpression.parse(text).text()).isEqualTo(text);
    }

    @Test
    void keepsACanonicalTextWithSingleSpaces() {
        CronExpression expression = CronExpression.parse("  0\t2  * *   * ");

        assertThat(expression.text()).isEqualTo("0 2 * * *");
        assertThat(expression).hasToString("0 2 * * *").isEqualTo(CronExpression.parse("0 2 * * *"));
        assertThat(expression.hashCode())
                .isEqualTo(CronExpression.parse("0 2 * * *").hashCode());
        assertThat(expression).isNotEqualTo("0 2 * * *");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "   ",
                "* * * *",
                "0 * * * * *",
                "0 0 * * * 2026",
                "@daily",
                "60 * * * *",
                "* 24 * * *",
                "* * 0 * *",
                "* * * 13 *",
                "* * * * 8",
                "0 0 31 2 *",
                "0 0 30 2 *",
                "1-2-3 * * * *",
                "*/0 * * * *",
                "0 0 ? * *",
                "0 0 L * *",
                "0 0 * * MON#2",
                "0 0 * * *\n",
                "0 0 * * *; rm -rf /",
                "０ 0 * * *"
            })
    void rejectsAnythingElseAsInvalidCron(String text) {
        assertThatThrownBy(() -> CronExpression.parse(text))
                .isInstanceOfSatisfying(
                        ValidationFailedException.class,
                        e -> assertThat(e.fieldErrors())
                                .containsExactly(new FieldError("schedule.expression", CronExpression.INVALID_CRON)));
    }

    @Test
    void rejectsOverlongTextBeforeParsing() {
        String text = "0 0 * * " + "1,".repeat(64) + "2";

        assertThatThrownBy(() -> CronExpression.parse("tasks.a.expression", text))
                .isInstanceOfSatisfying(
                        ValidationFailedException.class,
                        e -> assertThat(e.fieldErrors())
                                .containsExactly(new FieldError("tasks.a.expression", CronExpression.INVALID_CRON)));
    }

    @Test
    void matchesWallClockMinutesOnly() {
        CronExpression expression = CronExpression.parse("30 1 * * *");

        assertThat(expression.matches(LocalDateTime.of(2026, 11, 1, 1, 30))).isTrue();
        assertThat(expression.matches(LocalDateTime.of(2026, 11, 1, 1, 30, 1))).isFalse();
        assertThat(expression.matches(LocalDateTime.of(2026, 11, 1, 1, 31))).isFalse();
    }

    @Test
    void nextMatchIsStrictlyAfterAndIgnoresSeconds() {
        CronExpression expression = CronExpression.parse("30 1 * * *");

        assertThat(expression.nextMatchAfter(LocalDateTime.of(2026, 11, 1, 1, 29, 59)))
                .contains(LocalDateTime.of(2026, 11, 1, 1, 30));
        assertThat(expression.nextMatchAfter(LocalDateTime.of(2026, 11, 1, 1, 30)))
                .contains(LocalDateTime.of(2026, 11, 2, 1, 30));
        assertThat(expression.nextMatchAfter(LocalDateTime.of(2026, 11, 1, 1, 30, 30)))
                .contains(LocalDateTime.of(2026, 11, 2, 1, 30));
    }

    @Test
    void dayOfMonthAndDayOfWeekMatchEitherAsInUnixCron() {
        CronExpression expression = CronExpression.parse("0 0 13 * FRI");

        // 2026-11-06 is a Friday but not the 13th; 2026-12-13 is the 13th but a Sunday.
        assertThat(expression.matches(LocalDateTime.of(2026, 11, 6, 0, 0))).isTrue();
        assertThat(expression.matches(LocalDateTime.of(2026, 12, 13, 0, 0))).isTrue();
        assertThat(expression.matches(LocalDateTime.of(2026, 11, 7, 0, 0))).isFalse();
    }

    @Test
    void sundayIsZeroOrSeven() {
        LocalDateTime sunday = LocalDateTime.of(2026, 11, 1, 0, 0);

        assertThat(CronExpression.parse("0 0 * * 0").matches(sunday)).isTrue();
        assertThat(CronExpression.parse("0 0 * * 7").matches(sunday)).isTrue();
        assertThat(CronExpression.parse("0 0 * * SUN").matches(sunday)).isTrue();
    }
}
