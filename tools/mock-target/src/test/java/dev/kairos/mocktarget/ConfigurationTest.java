package dev.kairos.mocktarget;

import static org.assertj.core.api.Assertions.*;

import dev.kairos.mocktarget.behavior.infrastructure.RequestVerification;
import dev.kairos.mocktarget.config.MockTargetProperties;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigurationTest {
    @Test
    void disabledVerificationAcceptsUnsignedCalls() {
        assertThat(new RequestVerification(List.of(), Clock.systemUTC())
                        .verify("GET", "/behave", "", new byte[0], "", ""))
                .isTrue();
    }

    @Test
    void verificationKeysAreValidatedWithoutEchoingSecrets() {
        for (List<String> keys : List.of(
                List.of("x".repeat(129)), List.of("bad"), List.of("invalid-one", "invalid-two", "invalid-three")))
            assertThatThrownBy(() -> new RequestVerification(keys, Clock.systemUTC()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(keys.getFirst());
    }

    @Test
    void propertyDiagnosticsRedactKeysAndIntervalsAreBounded() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = "ksig_v1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        assertThat(new MockTargetProperties(10, 100, Duration.ofSeconds(1), Path.of("/tmp/stats.json"), List.of(key))
                        .toString())
                .doesNotContain(key);
        for (Duration interval : List.of(Duration.ZERO, Duration.ofMillis(999), Duration.ofHours(2)))
            assertThatThrownBy(() -> new MockTargetProperties(10, 100, interval, Path.of("/tmp/stats.json"), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
    }
}
