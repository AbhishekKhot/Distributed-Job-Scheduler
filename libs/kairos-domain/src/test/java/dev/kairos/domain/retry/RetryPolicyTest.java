package dev.kairos.domain.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RetryPolicyTest {

    private static final RetryPolicy JOB = new RetryPolicy(1, RetryStrategy.FIXED, 10, 10, 3_600);
    private static final RetryPolicy ENDPOINT = new RetryPolicy(2, RetryStrategy.FULL_JITTER, 20, 60, 3_600);
    private static final RetryPolicy TEAM = new RetryPolicy(3, RetryStrategy.EXPONENTIAL_JITTER, 5, 60, 3_600);

    @Test
    void platformDefaultMatchesTheLld() {
        RetryPolicy policy = RetryPolicy.PLATFORM_DEFAULT;

        assertThat(policy.maxRetries()).isEqualTo(5);
        assertThat(policy.strategy()).isEqualTo(RetryStrategy.EXPONENTIAL_JITTER);
        assertThat(policy.baseDelay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.maxDelay()).isEqualTo(Duration.ofMinutes(10));
        assertThat(policy.maxAge()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void resolvesJobThenEndpointThenTeamThenPlatform() {
        RetryPolicy platform = RetryPolicy.PLATFORM_DEFAULT;

        assertThat(RetryPolicy.resolve(JOB, ENDPOINT, TEAM, platform)).isSameAs(JOB);
        assertThat(RetryPolicy.resolve(null, ENDPOINT, TEAM, platform)).isSameAs(ENDPOINT);
        assertThat(RetryPolicy.resolve(null, null, TEAM, platform)).isSameAs(TEAM);
        assertThat(RetryPolicy.resolve(null, null, null, platform)).isSameAs(platform);
        assertThat(RetryPolicy.resolve(JOB, null, null, platform)).isSameAs(JOB);
    }

    @ParameterizedTest
    @CsvSource({
        "-1, 30, 600, 86400, retryPolicy.maxRetries",
        "21, 30, 600, 86400, retryPolicy.maxRetries",
        "5, 0, 600, 86400, retryPolicy.baseDelaySec",
        "5, 3601, 3601, 86400, retryPolicy.baseDelaySec",
        "5, 30, 29, 86400, retryPolicy.maxDelaySec",
        "5, 30, 86401, 86400, retryPolicy.maxDelaySec",
        "5, 30, 600, 59, retryPolicy.maxAgeSec",
        "5, 30, 600, 604801, retryPolicy.maxAgeSec"
    })
    void rejectsValuesOutOfRange(int maxRetries, int baseDelaySec, int maxDelaySec, int maxAgeSec, String field) {
        assertThatExceptionOfType(ValidationFailedException.class)
                .isThrownBy(() -> new RetryPolicy(
                        maxRetries, RetryStrategy.EXPONENTIAL_JITTER, baseDelaySec, maxDelaySec, maxAgeSec))
                .satisfies(e -> assertThat(e.fieldErrors()).containsExactly(new FieldError(field, FieldError.RANGE)));
    }

    @ParameterizedTest
    @CsvSource({"0, 1, 1, 60", "20, 3600, 86400, 604800"})
    void acceptsTheEdgesOfEachRange(int maxRetries, int baseDelaySec, int maxDelaySec, int maxAgeSec) {
        RetryPolicy policy = new RetryPolicy(maxRetries, RetryStrategy.FIXED, baseDelaySec, maxDelaySec, maxAgeSec);

        assertThat(policy.maxRetries()).isEqualTo(maxRetries);
    }

    @ParameterizedTest
    @CsvSource({"0, true", "4, true", "5, false", "6, false"})
    void allowsRetriesUntilTheLimit(int retriesUsed, boolean allowed) {
        assertThat(RetryPolicy.PLATFORM_DEFAULT.allowsRetry(retriesUsed)).isEqualTo(allowed);
    }

    @Test
    void zeroRetriesNeverRetries() {
        assertThat(new RetryPolicy(0, RetryStrategy.FIXED, 1, 1, 60).allowsRetry(0))
                .isFalse();
    }

    @Test
    void rejectsNegativeRetriesUsed() {
        assertThatIllegalArgumentException().isThrownBy(() -> RetryPolicy.PLATFORM_DEFAULT.allowsRetry(-1));
    }

    @Test
    void delayUsesTheStrategyBaseAndCap() {
        assertThat(JOB.delayBefore(3, Draws.highest())).isEqualTo(Duration.ofSeconds(10));
        assertThat(RetryPolicy.PLATFORM_DEFAULT.delayBefore(2, Draws.lowest())).isEqualTo(Duration.ofMinutes(1));
        assertThat(RetryPolicy.PLATFORM_DEFAULT.delayBefore(9, Draws.highest())).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void unknownOutcomePolicyDefaultsToRetryingWithTheIdempotencyKey() {
        assertThat(UnknownOutcomePolicy.DEFAULT).isEqualTo(UnknownOutcomePolicy.RETRY_WITH_IDEMPOTENCY_KEY);
        assertThat(UnknownOutcomePolicy.RETRY_WITH_IDEMPOTENCY_KEY.retries()).isTrue();
        assertThat(UnknownOutcomePolicy.REVIEW.retries()).isFalse();
    }
}
