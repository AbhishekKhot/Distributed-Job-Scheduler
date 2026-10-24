package dev.kairos.watcher.delayed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.domain.model.Trigger;
import dev.kairos.engine.lock.RedisConnector;
import dev.kairos.engine.lock.RedisProperties;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosRedis;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Shared Redis pacing limits across watcher instances. */
@KairosIntegrationTest(postgres = false, kafka = false)
class DelayedPacerIntegrationTest {
    @Test
    void teamQuotaAndRedriveLimitAreSharedAcrossConnectors() {
        try (RedisConnector first = connector();
                RedisConnector second = connector()) {
            String teamKey = key();
            UUID team = UUID.randomUUID();
            UUID job = UUID.randomUUID();
            assertThat(first.reserveDelayedPace(teamKey, team, job, Trigger.SCHEDULE, 2))
                    .isTrue();
            assertThat(second.reserveDelayedPace(teamKey, team, job, Trigger.REDRIVE, 2))
                    .isTrue();
            assertThat(first.reserveDelayedPace(teamKey, team, job, Trigger.REDRIVE, 2))
                    .isFalse();

            String redriveKey = key();
            for (int i = 0; i < 100; i++) {
                assertThat(first.reserveDelayedPace(redriveKey, UUID.randomUUID(), job, Trigger.REDRIVE, 2))
                        .isTrue();
            }
            assertThat(second.reserveDelayedPace(redriveKey, UUID.randomUUID(), job, Trigger.REDRIVE, 2))
                    .isFalse();
        }
    }

    @Test
    void backfillJobAndGlobalLimitsAreShared() {
        try (RedisConnector first = connector();
                RedisConnector second = connector()) {
            String jobKey = key();
            UUID team = UUID.randomUUID();
            UUID job = UUID.randomUUID();
            for (int i = 0; i < 100; i++) {
                assertThat(first.reserveDelayedPace(jobKey, team, job, Trigger.BACKFILL, 2_000))
                        .isTrue();
            }
            assertThat(second.reserveDelayedPace(jobKey, team, job, Trigger.BACKFILL, 2_000))
                    .isFalse();

            String globalKey = key();
            for (int i = 0; i < 1_000; i++) {
                assertThat(first.reserveDelayedPace(globalKey, team, UUID.randomUUID(), Trigger.BACKFILL, 2_000))
                        .isTrue();
            }
            assertThat(second.reserveDelayedPace(globalKey, team, UUID.randomUUID(), Trigger.BACKFILL, 2_000))
                    .isFalse();
        }
    }

    private static RedisConnector connector() {
        KairosRedis redis = KairosRedis.get();
        RedisConnector connector = RedisConnector.create(
                RedisProperties.of(redis.host(), redis.port(), redis.username(), redis.password()),
                new SimpleMeterRegistry(),
                Clock.systemUTC());
        connector.connect();
        return connector;
    }

    private static String key() {
        return "kairos:pace:delayed:test:" + UUID.randomUUID();
    }
}
