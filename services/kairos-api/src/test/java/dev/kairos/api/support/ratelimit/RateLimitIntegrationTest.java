package dev.kairos.api.support.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.api.ApiTestFixtures;
import dev.kairos.api.ProbeApplicationTest;
import dev.kairos.api.support.problem.ApiException;
import dev.kairos.api.support.ratelimit.infrastructure.Bucket4jRateLimiter;
import dev.kairos.api.support.ratelimit.infrastructure.RateLimitRedis;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.engine.lock.RedisProperties;
import dev.kairos.testsupport.KairosRedis;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.RepositoryFiles;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** Rate limits against Redis: 429 with {@code Retry-After} over the limit, 503 when Redis is unavailable. */
class RateLimitIntegrationTest extends ProbeApplicationTest {

    @Test
    void theLimitAnswers429WithRetryAfter() {
        String token = newUser(ApiTestFixtures.insertTeam()).token();

        for (int i = 0; i < 20; i++) {
            assertThat(send("GET", "/v1/probe/login-like", token, null).statusCode())
                    .isEqualTo(200);
        }
        HttpResponse<String> limited = send("GET", "/v1/probe/login-like", token, null);

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.body()).contains("/errors/rate-limited");
        assertThat(limited.headers().firstValue("Retry-After"))
                .hasValueSatisfying(
                        seconds -> assertThat(Long.parseLong(seconds)).isBetween(1L, 60L));
    }

    @Test
    void bucketsAreKeyedByHashedSubjects() {
        String key = Bucket4jRateLimiter.key(RateLimit.LOGIN_PER_ACCOUNT, "someone@example.com");

        assertThat(key).matches("kairos:rl:login-account:[0-9a-f]{32}").doesNotContain("example");
    }

    @Test
    void aStoppedRedisFailsClosedWith503() {
        String password = ApiTestFixtures.randomHex();
        try (GenericContainer<?> container = redisContainer(password)) {
            container.start();
            RedisProperties properties = RedisProperties.of(
                    container.getHost(), container.getMappedPort(6379), KairosRedis.USERNAME, password);
            try (RateLimitRedis redis = RateLimitRedis.create(properties, MutableClock.at(Instant.now()))) {
                RateLimiter limiter = new Bucket4jRateLimiter(redis, policies(1));
                String subject = UUID.randomUUID().toString();
                limiter.consume(RateLimit.SIGNUP_PER_IP, subject);
                assertThat(redis.ping()).isTrue();

                container.stop();

                assertThatThrownBy(() -> limiter.consume(RateLimit.SIGNUP_PER_IP, subject))
                        .isInstanceOfSatisfying(ApiException.class, e -> {
                            assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAVAILABLE);
                            assertThat(e.retryAfter()).isPresent();
                        });
                assertThat(redis.ping()).isFalse();
            }
        }
    }

    @Test
    void anUnreachableRedisFailsClosedAndWaitsBeforeReconnecting() {
        MutableClock clock = MutableClock.at(Instant.parse("2026-10-21T10:00:00Z"));
        try (RateLimitRedis redis = RateLimitRedis.create(
                RedisProperties.of("127.0.0.1", 1, KairosRedis.USERNAME, ApiTestFixtures.randomHex()), clock)) {
            RateLimiter limiter = new Bucket4jRateLimiter(redis, policies(5));

            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> limiter.consume(RateLimit.LOGIN_PER_IP, "203.0.113.7"))
                        .isInstanceOfSatisfying(
                                ApiException.class,
                                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAVAILABLE));
            }
            clock.advance(Duration.ofSeconds(2));
            assertThat(redis.ping()).isFalse();
        }
    }

    @Test
    void aClosedConnectionFailsClosed() {
        KairosRedis shared = KairosRedis.get();
        RateLimitRedis redis = RateLimitRedis.create(
                RedisProperties.of(shared.host(), shared.port(), shared.username(), shared.password()),
                MutableClock.at(Instant.now()));
        redis.close();

        assertThatThrownBy(() -> new Bucket4jRateLimiter(redis, policies(5)).consume(RateLimit.TEAM_API, "t"))
                .isInstanceOf(ApiException.class);
    }

    private static Map<RateLimit, RateLimitPolicy> policies(long capacity) {
        Map<RateLimit, RateLimitPolicy> policies = new EnumMap<>(RateLimit.class);
        for (RateLimit limit : RateLimit.values()) {
            policies.put(limit, new RateLimitPolicy(capacity, Duration.ofMinutes(1)));
        }
        return policies;
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
}
