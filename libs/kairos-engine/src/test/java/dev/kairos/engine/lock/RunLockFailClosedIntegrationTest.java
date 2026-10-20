package dev.kairos.engine.lock;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.domain.id.RunId;
import dev.kairos.domain.run.Fence;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosRedis;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.RepositoryFiles;
import dev.kairos.testsupport.TestIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Fail-closed behaviour: with Redis stopped or unreachable, every operation reports {@code UNAVAILABLE} within the
 * command timeout and nothing throws. The test runs its own Redis container (same image, start script and ACL as
 * compose), because it stops it.
 */
@KairosIntegrationTest(postgres = false, kafka = false, redis = false)
class RunLockFailClosedIntegrationTest {

    private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(500);
    /** Scheduling slack on top of the command timeout for a loaded build machine. */
    private static final Duration SLACK = Duration.ofMillis(500);

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Fence FENCE = new Fence(1);

    @Test
    void acquireReturnsUnavailableWithinTheTimeoutOnceRedisIsStopped() {
        String password = randomHex();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (GenericContainer<?> container = redisContainer(password)) {
            container.start();
            RedisProperties properties = RedisProperties.of(
                    container.getHost(), container.getMappedPort(6379), KairosRedis.USERNAME, password);
            try (RedisConnector connector = RedisLockFixtures.connected(properties, registry)) {
                RunLockPort lock = connector.runLock("executor-a");
                SentMarkerPort markers = connector.sentMarkers();
                RunId runId = TestIds.runId(20);
                AcquireResult held = lock.acquire(runId, FENCE, TTL);
                assertThat(held).isInstanceOf(AcquireResult.Acquired.class);
                RunLock owned = ((AcquireResult.Acquired) held).lock();

                container.stop();

                long started = System.nanoTime();
                AcquireResult result = lock.acquire(TestIds.runId(21), FENCE, TTL);
                Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

                assertThat(result).isEqualTo(new AcquireResult.Unavailable());
                assertThat(result.outcome()).isEqualTo(AcquireResult.Outcome.UNAVAILABLE);
                assertThat(elapsed).isLessThan(COMMAND_TIMEOUT.plus(SLACK));
                assertThat(lock.remaining(owned)).isEqualTo(new RemainingResult.Unavailable());
                assertThat(lock.remaining(owned).timeLeft()).isEmpty();
                assertThat(lock.extend(owned, TTL)).isEqualTo(ExtendResult.UNAVAILABLE);
                assertThat(lock.release(owned)).isEqualTo(ReleaseResult.UNAVAILABLE);
                assertThat(lock.inspect(runId)).isEqualTo(new LockInspection.Unavailable());
                SentMarker marker = new SentMarker(runId, 1, 0);
                assertThat(markers.mark(marker)).isEqualTo(SentMarkerPort.MarkResult.UNAVAILABLE);
                assertThat(markers.check(marker)).isEqualTo(SentMarkerPort.MarkerCheck.UNAVAILABLE);
                assertThat(connector.topologyProbe().read()).isEmpty();
                assertThat(registry.counter(RedisMetrics.ACQUIRE, "outcome", "unavailable")
                                .count())
                        .isEqualTo(1);
                assertThat(registry.counter(RedisMetrics.UNAVAILABLE).count()).isGreaterThanOrEqualTo(8);
            }
        }
    }

    @Test
    void anUnreachableRedisFailsClosedAndIsNotRetriedBeforeTheReconnectDelay() {
        MutableClock clock = MutableClock.at(Instant.parse("2026-10-20T10:00:00Z"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // Nothing listens on port 1, so the connection is refused at once.
        RedisProperties properties = RedisProperties.of("127.0.0.1", 1, "kairos", randomHex());
        try (RedisConnector connector = RedisConnector.create(properties, registry, clock)) {
            RunLockPort lock = connector.runLock("executor-a");

            long started = System.nanoTime();
            AcquireResult first = lock.acquire(TestIds.runId(22), FENCE, TTL);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            AcquireResult second = lock.acquire(TestIds.runId(22), FENCE, TTL);
            clock.advance(properties.reconnectDelay());
            AcquireResult third = lock.acquire(TestIds.runId(22), FENCE, TTL);

            assertThat(first).isEqualTo(new AcquireResult.Unavailable());
            assertThat(elapsed).isLessThan(COMMAND_TIMEOUT.plus(SLACK));
            assertThat(second).isEqualTo(new AcquireResult.Unavailable());
            assertThat(third).isEqualTo(new AcquireResult.Unavailable());
            assertThat(registry.counter(RedisMetrics.UNAVAILABLE).count()).isEqualTo(3);
        }
    }

    @Test
    void aClosedConnectorFailsClosed() {
        RedisConnector connector = RedisConnector.create(
                RedisProperties.of("127.0.0.1", 1, "kairos", randomHex()),
                new SimpleMeterRegistry(),
                MutableClock.at(Instant.parse("2026-10-20T10:00:00Z")));
        connector.close();
        connector.connect();

        assertThat(connector.runLock("executor-a").acquire(TestIds.runId(23), FENCE, TTL))
                .isEqualTo(new AcquireResult.Unavailable());
    }

    private static GenericContainer<?> redisContainer(String password) {
        String image = KairosRedis.IMAGE;
        String name = image.substring(0, image.indexOf(':'));
        String digest = image.substring(image.indexOf('@') + 1);
        return new GenericContainer<>(DockerImageName.parse(name + "@" + digest))
                .withExposedPorts(6379)
                .withEnv("KAIROS_REDIS_PASSWORD", password)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(RepositoryFiles.resolve("deploy/compose/redis/start-redis.sh"), 0444),
                        "/opt/kairos/start-redis.sh")
                .withCreateContainerCmdModifier(cmd ->
                        cmd.withEntrypoint("sh", "/opt/kairos/start-redis.sh").withUser("redis"))
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\s", 1));
    }

    private static String randomHex() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
