package dev.kairos.executor.call.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.domain.retry.CallResult;
import dev.kairos.http.client.HttpCallOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies endpoint request admission and breaker transitions. */
class EndpointProtectionsTest {

    @Test
    void rateLimitDefersWithoutRecordingAnEndpointAttempt() {
        EndpointProtections protections = new EndpointProtections(new SimpleMeterRegistry());
        UUID endpoint = UUID.randomUUID();

        EndpointProtections.Admission first = protections.acquire(endpoint, 1);
        assertThat(first.allowed()).isTrue();
        EndpointProtections.Admission second = protections.acquire(endpoint, 1);

        assertThat(second.allowed()).isFalse();
        assertThat(second.retryAfterMillis()).isPositive();
        protections.abandon(first);
    }

    @Test
    void opensAfterOneHundredCallsWithAtLeastFiftyPercentFailures() {
        EndpointProtections protections = new EndpointProtections(new SimpleMeterRegistry());
        UUID endpoint = UUID.randomUUID();
        Instant now = Instant.now();
        for (int call = 0; call < 100; call++) {
            EndpointProtections.Admission admission = protections.acquire(endpoint, 0);
            assertThat(admission.allowed()).isTrue();
            boolean success = call < 50;
            HttpCallOutcome outcome = success
                    ? new HttpCallOutcome.Received(
                            200, java.util.List.of(), dev.kairos.http.client.ResponseBody.empty(), now, now)
                    : new HttpCallOutcome.Failed(new CallResult.ConnectionFailed(true), "failed", now, now);
            protections.complete(admission, outcome, success);
        }

        assertThat(protections.acquire(endpoint, 0).allowed()).isFalse();
    }
}
