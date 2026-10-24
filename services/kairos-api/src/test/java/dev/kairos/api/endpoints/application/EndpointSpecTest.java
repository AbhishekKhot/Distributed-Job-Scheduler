package dev.kairos.api.endpoints.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.error.KairosException;
import dev.kairos.http.policy.TargetPolicy;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EndpointSpecTest {
    @Test
    void validatesAllowedHttpsTargetAndPathParameters() {
        EndpointSpec spec = spec("https://orders.internal/v1/{orderId}");
        TargetPolicy policy =
                TargetPolicy.builder().allowSuffix("internal").allowPort(443).build();

        spec.validateTarget(policy);
    }

    @Test
    void refusesPlainHttpTarget() {
        EndpointSpec spec = spec("http://orders.internal/v1");
        TargetPolicy policy =
                TargetPolicy.builder().allowSuffix("internal").allowPort(80).build();

        assertThatThrownBy(() -> spec.validateTarget(policy)).isInstanceOf(KairosException.class);
    }

    @Test
    void refusesMetadataAddress() {
        EndpointSpec spec = spec("https://169.254.169.254/latest/meta-data");
        TargetPolicy policy = TargetPolicy.builder()
                .allowCidr(dev.kairos.http.policy.Cidr.parse("0.0.0.0/0"))
                .allowPort(443)
                .build();

        assertThatThrownBy(() -> spec.validateTarget(policy)).isInstanceOf(KairosException.class);
    }

    @Test
    void refusesManagedHeadersAndPathParamsInQuery() {
        assertThatThrownBy(() -> new EndpointSpec(
                        "POST",
                        "https://orders.internal/v1",
                        Map.of("Authorization", "Bearer x"),
                        new EndpointSpec.Auth(EndpointSpec.AuthType.NONE, null, null, null, null, null),
                        null,
                        30000,
                        Set.of("2xx"),
                        Map.of(),
                        retry(),
                        new EndpointSpec.Limits(2, 10),
                        Map.of(),
                        EndpointSpec.UnknownOutcomePolicy.RETRY_WITH_IDEMPOTENCY_KEY,
                        new EndpointSpec.SuspendPolicy(true)))
                .isInstanceOf(KairosException.class);

        EndpointSpec spec = spec("https://orders.internal/v1?order={orderId}");
        TargetPolicy policy =
                TargetPolicy.builder().allowSuffix("internal").allowPort(443).build();
        assertThatThrownBy(() -> spec.validateTarget(policy)).isInstanceOf(KairosException.class);
    }

    private static EndpointSpec spec(String url) {
        return new EndpointSpec(
                "POST",
                url,
                Map.of(),
                new EndpointSpec.Auth(EndpointSpec.AuthType.NONE, null, null, null, null, null),
                null,
                30000,
                Set.of("2xx"),
                Map.of(),
                retry(),
                new EndpointSpec.Limits(2, 10),
                Map.of(),
                EndpointSpec.UnknownOutcomePolicy.RETRY_WITH_IDEMPOTENCY_KEY,
                new EndpointSpec.SuspendPolicy(true));
    }

    private static EndpointSpec.RetryPolicy retry() {
        return new EndpointSpec.RetryPolicy(3, EndpointSpec.RetryStrategy.FULL_JITTER, 1, 10, 600);
    }
}
