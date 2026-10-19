package dev.kairos.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins every database code to the value in LLD section 2. A failure here means a stored code
 * would be read as a different constant: fix the enum, never the expected table.
 */
class CodedEnumTest {

    static Stream<Arguments> codeTables() {
        return Stream.of(
                table(RunState.values(), RunState::fromCode, "WAITING=1, QUEUED=2, RUNNING=3, DELAYED=4"),
                table(Trigger.values(), Trigger::fromCode, "SCHEDULE=1, IMMEDIATE=2, MANUAL=3, BACKFILL=4, REDRIVE=5"),
                table(TargetType.values(), TargetType::fromCode, "JOB=1, WORKFLOW_TASK=2, WORKFLOW=3"),
                table(
                        Outcome.values(),
                        Outcome::fromCode,
                        "SUCCEEDED=1, FAILED_RETRYING=2, DEAD_LETTERED=3, CANCELLED=4, SKIPPED=5, UPSTREAM_FAILED=6,"
                                + " EXECUTOR_LOST=7"),
                table(
                        ErrorClass.values(),
                        ErrorClass::fromCode,
                        "TIMEOUT=1, DNS_FAILED=2, CONNECT_FAILED=3, TLS_FAILED=4, HTTP_4XX=5, HTTP_429=6, HTTP_5XX=7,"
                                + " UNKNOWN_OUTCOME=8, RESPONSE_TOO_LARGE=9, OUTPUT_MISSING=10, RENDER_FAILED=11,"
                                + " TARGET_NOT_ALLOWED=12, MAX_AGE_EXCEEDED=13, EXECUTOR_LOST=14"),
                table(DlqStatus.values(), DlqStatus::fromCode, "OPEN=1, ACKNOWLEDGED=2, REDRIVEN=3, DISCARDED=4"),
                table(RedriveMode.values(), RedriveMode::fromCode, "ORIGINAL=1, CURRENT=2"),
                table(ScheduleKind.values(), ScheduleKind::fromCode, "ONE_TIME=1, CRON=2, INTERVAL=3"),
                table(ScheduleStatus.values(), ScheduleStatus::fromCode, "ACTIVE=1, PAUSED=2, HELD=3"),
                table(MisfirePolicy.values(), MisfirePolicy::fromCode, "RUN_ALL=1, RUN_LATEST=2, SKIP=3"),
                table(
                        DstPolicy.values(),
                        DstPolicy::fromCode,
                        "GAP_SHIFT_FORWARD_OVERLAP_FIRST=1, GAP_SHIFT_FORWARD_OVERLAP_BOTH=2, GAP_SKIP_OVERLAP_FIRST=3,"
                                + " GAP_SKIP_OVERLAP_BOTH=4"),
                table(
                        WorkflowRunStatus.values(),
                        WorkflowRunStatus::fromCode,
                        "RUNNING=1, BLOCKED=2, SUCCEEDED=3, FAILED=4, CANCELLED=5"),
                table(
                        OutboxTopic.values(),
                        OutboxTopic::fromCode,
                        "RUN_HIGH=1, RUN_MEDIUM=2, RUN_LOW=3, RUN_CANCEL=4, RUN_COMPLETED=5, RUN_DEAD=6"),
                table(IdempotencyStatus.values(), IdempotencyStatus::fromCode, "IN_PROGRESS=1, DONE=2"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("codeTables")
    void codesMatchTheLld(String type, List<? extends CodedEnum> constants, IntFunction<?> fromCode, String expected) {
        String actual = constants.stream()
                .map(constant -> constant + "=" + constant.code())
                .collect(Collectors.joining(", "));

        assertThat(actual).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("codeTables")
    void fromCodeRoundTripsAndRejectsUnknownCodes(
            String type, List<? extends CodedEnum> constants, IntFunction<?> fromCode, String expected) {
        for (CodedEnum constant : constants) {
            assertThat(fromCode.apply(constant.code())).isSameAs(constant);
        }
        for (int unknown : new int[] {0, -1, constants.size() + 1, Short.MAX_VALUE}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> fromCode.apply(unknown))
                    .withMessageContaining(type);
        }
    }

    private static <E extends Enum<E> & CodedEnum> Arguments table(
            E[] constants, IntFunction<E> fromCode, String expected) {
        return Arguments.of(
                constants[0].getDeclaringClass().getSimpleName(), Arrays.asList(constants), fromCode, expected);
    }
}
