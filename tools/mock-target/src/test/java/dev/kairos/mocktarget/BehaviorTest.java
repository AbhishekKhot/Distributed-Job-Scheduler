package dev.kairos.mocktarget;

import static org.assertj.core.api.Assertions.*;

import dev.kairos.mocktarget.behavior.application.Behavior;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BehaviorTest {
    @ParameterizedTest
    @ValueSource(strings = {"healthy", "retryable", "client-error", "slow", "mixed"})
    void namedProfilesAreAvailableFromPathOrQuery(String profile) {
        assertThat(Behavior.resolve(profile, Map.of()).profile()).isEqualTo(profile);
        assertThat(Behavior.resolve(null, Behavior.query("profile=" + profile)).profile())
                .isEqualTo(profile);
    }

    @Test
    void explicitOverridesAndEncodedQueryValuesHaveBounds() {
        var value = Behavior.resolve("slow", Behavior.query("status=201&latencyMs=0&failRate=1&bodyBytes=%31%30"));
        assertThat(value.status()).isEqualTo(201);
        assertThat(value.latencyMs()).isZero();
        assertThat(value.failRate()).isEqualTo(1);
        assertThat(value.bodyBytes()).isEqualTo(10);
        assertThat(Behavior.resolve(null, Behavior.query(null)).status()).isEqualTo(200);
        assertThat(Behavior.resolve(null, Behavior.query("")).bodyBytes()).isNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "status=199",
                "status=600",
                "status=x",
                "status=2147483648",
                "latencyMs=-1",
                "latencyMs=60001",
                "failRate=NaN",
                "failRate=Infinity",
                "failRate=-0.1",
                "failRate=1.1",
                "bodyBytes=-1",
                "bodyBytes=1048577",
                "bodyBytes=2.5",
                "profile=missing",
                "unknown=1",
                "status=200&status=500",
                "status=200&%73tatus=500",
                "status",
                "=200",
                "status=%",
                "status=200&"
            })
    void rejectsMalformedOrUnboundedBehavior(String query) {
        assertThatThrownBy(() -> Behavior.resolve(null, Behavior.query(query)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAmbiguousProfilesAndOversizedParameters() {
        assertThatThrownBy(() -> Behavior.resolve("healthy", Behavior.query("profile=slow")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Behavior.query("x".repeat(4097))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Behavior.resolve(null, Map.of("status", new String[] {"2".repeat(33)})))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
