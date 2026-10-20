package dev.kairos.engine.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.run.Fence;
import dev.kairos.testsupport.TestIds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Property binding and validation, node ids, and the beans with an unreachable Redis (they must still start). */
class RunLockConfigurationTest {

    private static final String PASSWORD = randomHex();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RunLockConfiguration.class)
            .withPropertyValues(
                    "kairos.redis.host=127.0.0.1",
                    "kairos.redis.port=1",
                    "kairos.redis.username=kairos",
                    "kairos.redis.password=" + PASSWORD);

    @Test
    void startsWhileRedisIsDownAndFailsClosed() {
        runner.withPropertyValues("kairos.redis.node-id=executor-0").run(context -> {
            assertThat(context).hasNotFailed();
            RedisProperties properties = context.getBean(RedisProperties.class);
            assertThat(properties.commandTimeout()).isEqualTo(Duration.ofMillis(500));
            assertThat(properties.tls()).isFalse();
            assertThat(properties.toString()).doesNotContain(PASSWORD).contains("password=<redacted>");
            AcquireResult result =
                    context.getBean(RunLockPort.class).acquire(TestIds.runId(1), new Fence(1), Duration.ofSeconds(5));
            assertThat(result).isEqualTo(new AcquireResult.Unavailable());
            assertThat(context.getBean(SentMarkerPort.class).check(new SentMarker(TestIds.runId(1), 1, 0)))
                    .isEqualTo(SentMarkerPort.MarkerCheck.UNAVAILABLE);
            assertThat(context.getBean(RedisTopologyProbe.class).read()).isEmpty();
        });
    }

    @Test
    void rejectsInvalidSettings() {
        runner.withPropertyValues("kairos.redis.host=bad host")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("kairos.redis.password=")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("kairos.redis.node-id=a:b")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("kairos.redis.command-timeout=1m")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("kairos.redis.tls=true", "kairos.redis.trust-certificate=/nonexistent/ca.pem")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void tlsWithATrustCertificateFileIsAccepted(@TempDir Path directory) throws IOException {
        Path certificate = Files.writeString(directory.resolve("ca.pem"), "placeholder");

        runner.withPropertyValues("kairos.redis.tls=true", "kairos.redis.trust-certificate=" + certificate)
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("kairos.redis.tls=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void timeoutsAreRangeChecked() {
        assertThatThrownBy(() -> new RedisProperties(
                        "h",
                        1,
                        "u",
                        "p",
                        false,
                        null,
                        Duration.ZERO,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisProperties(
                        "h",
                        1,
                        "u",
                        "p",
                        false,
                        null,
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(1),
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisProperties(
                        "h",
                        1,
                        "u",
                        "p",
                        false,
                        null,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(1),
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nodeIdPrefersConfigurationThenHostNameThenRandom() {
        SecureRandom random = new SecureRandom();

        assertThat(RunLockConfiguration.nodeId("configured", "host-1", random)).isEqualTo("configured");
        assertThat(RunLockConfiguration.nodeId(null, "kairos-executor-5d8f9", random))
                .isEqualTo("kairos-executor-5d8f9");
        assertThat(RunLockConfiguration.nodeId(null, "bad:host", random)).matches("^node-[0-9a-f]{16}$");
        assertThat(RunLockConfiguration.nodeId(null, null, random)).matches("^node-[0-9a-f]{16}$");
        assertThatThrownBy(() -> RunLockConfiguration.nodeId("bad:id", null, random))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A password generated for every run, so no credential-like literal sits in the test. */
    private static String randomHex() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
