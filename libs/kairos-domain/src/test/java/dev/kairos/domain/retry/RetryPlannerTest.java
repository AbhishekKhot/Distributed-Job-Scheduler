package dev.kairos.domain.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.model.ErrorClass;
import dev.kairos.domain.shard.Shard;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RetryPlannerTest {

    private static final Instant START = Instant.parse("2026-10-20T09:00:00Z");
    private static final RunId RUN =
            new KairosIdFactory(Clock.fixed(START, ZoneOffset.UTC), new SecureRandom()).newRunId(Shard.of(7));
    private static final Classification.Failure RETRYABLE_5XX =
            new Classification.Failure(ErrorClass.HTTP_5XX, FailureKind.RETRYABLE);
    private static final Classification.Failure TIMEOUT_AFTER_SEND =
            new Classification.Failure(ErrorClass.TIMEOUT, FailureKind.UNKNOWN_OUTCOME);

    private static RetryPlanner plannerAt(Instant now, RandomGenerator random) {
        return new RetryPlanner(Clock.fixed(now, ZoneOffset.UTC), () -> random);
    }

    private static FailedAttempt failed(int attempt, Classification.Failure failure) {
        return failed(attempt, 1, START, failure, null);
    }

    private static FailedAttempt failed(
            int attempt,
            int seriesStart,
            Instant seriesStartedAt,
            Classification.Failure failure,
            @Nullable Duration retryAfter) {
        return new FailedAttempt(RUN, attempt, seriesStart, seriesStartedAt, failure, retryAfter);
    }

    private static RetryDecision plan(FailedAttempt failed, UnknownOutcomePolicy unknownOutcomePolicy) {
        return plannerAt(START, Draws.lowest()).plan(failed, RetryPolicy.PLATFORM_DEFAULT, unknownOutcomePolicy);
    }

    @Test
    void retryableFailureIsRetriedWithBackoffAndTheSameIdempotencyKey() {
        RetryDecision decision = plan(failed(1, RETRYABLE_5XX), UnknownOutcomePolicy.DEFAULT);

        assertThat(decision).isEqualTo(new RetryDecision.Retry(RUN, 2, Duration.ofSeconds(30), START.plusSeconds(30)));
    }

    @Test
    void nextRetryNumberComesFromThePositionInTheSeries() {
        RetryDecision decision = plan(failed(3, RETRYABLE_5XX), UnknownOutcomePolicy.DEFAULT);

        assertThat(decision).isEqualTo(new RetryDecision.Retry(RUN, 4, Duration.ofMinutes(2), START.plusSeconds(120)));
    }

    @ParameterizedTest
    @EnumSource(
            value = ErrorClass.class,
            names = {"HTTP_4XX", "TARGET_NOT_ALLOWED", "RENDER_FAILED", "RESPONSE_TOO_LARGE", "OUTPUT_MISSING"})
    void nonRetryableFailureIsDeadLetteredWithItsErrorClass(ErrorClass errorClass) {
        RetryDecision decision = plan(
                failed(1, new Classification.Failure(errorClass, FailureKind.NON_RETRYABLE)),
                UnknownOutcomePolicy.DEFAULT);

        assertThat(decision).isEqualTo(new RetryDecision.DeadLetter(errorClass));
    }

    @Test
    void unknownOutcomeUnderReviewIsDeadLetteredAsUnknownOutcome() {
        RetryDecision decision = plan(failed(1, TIMEOUT_AFTER_SEND), UnknownOutcomePolicy.REVIEW);

        assertThat(decision).isEqualTo(new RetryDecision.DeadLetter(ErrorClass.UNKNOWN_OUTCOME));
    }

    @Test
    void unknownOutcomeIsRetriedWithTheRunIdAsIdempotencyKeyByDefault() {
        RetryDecision decision = plan(failed(2, TIMEOUT_AFTER_SEND), UnknownOutcomePolicy.RETRY_WITH_IDEMPOTENCY_KEY);

        assertThat(decision).isInstanceOfSatisfying(RetryDecision.Retry.class, retry -> {
            assertThat(retry.idempotencyKey()).isEqualTo(RUN);
            assertThat(retry.nextAttempt()).isEqualTo(3);
        });
    }

    @Test
    void reviewDoesNotAffectOrdinaryRetryableFailures() {
        assertThat(plan(failed(1, RETRYABLE_5XX), UnknownOutcomePolicy.REVIEW)).isInstanceOf(RetryDecision.Retry.class);
    }

    @Test
    void deadLettersWithTheLastErrorClassOnceRetriesAreUsedUp() {
        assertThat(plan(failed(5, RETRYABLE_5XX), UnknownOutcomePolicy.DEFAULT))
                .isInstanceOf(RetryDecision.Retry.class);
        assertThat(plan(failed(6, RETRYABLE_5XX), UnknownOutcomePolicy.DEFAULT))
                .isEqualTo(new RetryDecision.DeadLetter(ErrorClass.HTTP_5XX));
        assertThat(plan(failed(6, TIMEOUT_AFTER_SEND), UnknownOutcomePolicy.DEFAULT))
                .isEqualTo(new RetryDecision.DeadLetter(ErrorClass.TIMEOUT));
    }

    @Test
    void redrivenSeriesGetsAFreshSetOfRetries() {
        Instant redrivenAt = START.plus(Duration.ofHours(2));

        RetryDecision decision = plannerAt(redrivenAt, Draws.lowest())
                .plan(
                        failed(7, 7, redrivenAt, RETRYABLE_5XX, null),
                        RetryPolicy.PLATFORM_DEFAULT,
                        UnknownOutcomePolicy.DEFAULT);

        assertThat(decision)
                .isEqualTo(new RetryDecision.Retry(RUN, 8, Duration.ofSeconds(30), redrivenAt.plusSeconds(30)));
    }

    @Test
    void retryAfterLengthensTheDelayUpToTheCap() {
        Classification.Failure tooMany = new Classification.Failure(ErrorClass.HTTP_429, FailureKind.RETRYABLE);
        RetryPlanner planner = plannerAt(START, Draws.lowest());
        RetryPolicy policy = RetryPolicy.PLATFORM_DEFAULT;

        assertThat(planner.plan(
                        failed(1, 1, START, tooMany, Duration.ofSeconds(90)), policy, UnknownOutcomePolicy.DEFAULT))
                .isEqualTo(new RetryDecision.Retry(RUN, 2, Duration.ofSeconds(90), START.plusSeconds(90)));
        assertThat(planner.plan(
                        failed(1, 1, START, tooMany, Duration.ofHours(1)), policy, UnknownOutcomePolicy.DEFAULT))
                .isEqualTo(new RetryDecision.Retry(RUN, 2, Duration.ofMinutes(10), START.plusSeconds(600)));
        assertThat(planner.plan(
                        failed(1, 1, START, tooMany, Duration.ofSeconds(5)), policy, UnknownOutcomePolicy.DEFAULT))
                .isEqualTo(new RetryDecision.Retry(RUN, 2, Duration.ofSeconds(30), START.plusSeconds(30)));
    }

    @Test
    void deadLettersWhenTheRetryWouldBeDuePastTheMaximumAge() {
        RetryPolicy policy = new RetryPolicy(5, RetryStrategy.FIXED, 60, 60, 3_600);
        Instant late = START.plus(Duration.ofMinutes(59)).plusSeconds(1);

        RetryDecision decision =
                plannerAt(late, Draws.lowest()).plan(failed(2, RETRYABLE_5XX), policy, UnknownOutcomePolicy.DEFAULT);

        assertThat(decision).isEqualTo(new RetryDecision.DeadLetter(ErrorClass.MAX_AGE_EXCEEDED));
    }

    @Test
    void retryDueExactlyAtTheMaximumAgeIsStillAllowed() {
        RetryPolicy policy = new RetryPolicy(5, RetryStrategy.FIXED, 60, 60, 3_600);
        Instant now = START.plus(Duration.ofMinutes(59));

        RetryDecision decision =
                plannerAt(now, Draws.lowest()).plan(failed(2, RETRYABLE_5XX), policy, UnknownOutcomePolicy.DEFAULT);

        assertThat(decision)
                .isEqualTo(new RetryDecision.Retry(RUN, 3, Duration.ofMinutes(1), START.plusSeconds(3_600)));
    }

    @Test
    void defaultPolicyDeadLettersAboutFifteenMinutesAfterTheFirstFailure() {
        Instant now = START;
        int attempt = 1;
        RetryDecision decision = plannerAt(now, Draws.highest())
                .plan(failed(attempt, RETRYABLE_5XX), RetryPolicy.PLATFORM_DEFAULT, UnknownOutcomePolicy.DEFAULT);
        while (decision instanceof RetryDecision.Retry retry) {
            now = retry.dueAt();
            attempt = retry.nextAttempt();
            decision = plannerAt(now, Draws.highest())
                    .plan(failed(attempt, RETRYABLE_5XX), RetryPolicy.PLATFORM_DEFAULT, UnknownOutcomePolicy.DEFAULT);
        }

        assertThat(attempt).isEqualTo(6);
        assertThat(decision).isEqualTo(new RetryDecision.DeadLetter(ErrorClass.HTTP_5XX));
        assertThat(Duration.between(START, now)).isBetween(Duration.ofSeconds(930), Duration.ofSeconds(1_005));
    }

    @Test
    void validatesTheFailedAttempt() {
        assertThatIllegalArgumentException().isThrownBy(() -> failed(0, RETRYABLE_5XX));
        assertThatIllegalArgumentException().isThrownBy(() -> failed(2, 3, START, RETRYABLE_5XX, null));
        assertThatIllegalArgumentException().isThrownBy(() -> failed(2, 0, START, RETRYABLE_5XX, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> failed(1, 1, START, RETRYABLE_5XX, Duration.ofSeconds(-1)));
        assertThat(failed(4, 2, START, RETRYABLE_5XX, null).retriesUsed()).isEqualTo(2);
    }

    @Test
    void validatesTheRetryDecision() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RetryDecision.Retry(RUN, 1, Duration.ZERO, START));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetryDecision.Retry(RUN, 2, Duration.ofSeconds(-1), START));
    }
}
