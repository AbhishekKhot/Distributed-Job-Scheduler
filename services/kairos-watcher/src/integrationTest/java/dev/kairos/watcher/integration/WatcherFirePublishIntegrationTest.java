package dev.kairos.watcher.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.shard.Shard;
import dev.kairos.messaging.topic.KairosTopic;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.testsupport.Awaits;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosKafka;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.watcher.lease.ShardLeaseManager;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Fires a schedule through the full watcher context and relays its dispatch to Kafka. */
@KairosIntegrationTest
@ActiveProfiles("local")
@SpringBootTest(
        properties = {
            "management.server.port=0",
            "kairos.watcher.heartbeat-interval=1s",
            "kairos.watcher.poll-jitter=0ms"
        })
class WatcherFirePublishIntegrationTest {
    @Autowired
    private CellRouter cells;

    @Autowired
    private ShardLeaseManager leases;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        WatcherSchema.ensure(KairosPostgres.get());
    }

    @Test
    void scheduledJobIsMaterializedAndPublished() throws Exception {
        leases.reconcile();
        Cell cell = cells.forShard(128);
        JdbcClient control = JdbcClient.create(KairosPostgres.get().controlDataSource());
        KairosIdFactory ids = new KairosIdFactory(Clock.systemUTC(), new SecureRandom());
        UUID team = ids.newTeamId().value();
        UUID endpoint = ids.newEndpointId().value();
        UUID job = ids.newJobId(Shard.of(128)).value();
        Instant now = Instant.now();
        String jobSpec = "{\"endpoint\":\"target\",\"request\":{\"pathParams\":{\"id\":\"42\"}}}";
        control.sql("INSERT INTO scheduler.teams(team_id,slug,created_at,updated_at) VALUES (:id,:slug,:now,:now)")
                .param("id", team)
                .param("slug", "t303-" + team.toString().substring(28))
                .param("now", Timestamp.from(now))
                .update();
        control.sql("""
                INSERT INTO scheduler.api_endpoints(endpoint_id,team_id,name,version,spec,created_at,updated_at)
                VALUES (:id,:team,'target',1,CAST(:spec AS jsonb),:now,:now)
                """)
                .param("id", endpoint)
                .param("team", team)
                .param("spec", "{\"timeoutMs\":1500,\"auth\":{\"type\":\"NONE\"}}")
                .param("now", Timestamp.from(now))
                .update();
        cell.jdbc()
                .sql("""
                INSERT INTO scheduler.jobs(job_id,team_id,name,version,endpoint_id,spec,created_by,created_at,updated_at)
                VALUES (:id,:team,:name,1,:endpoint,CAST(:spec AS jsonb),:actor,:now,:now)
                """)
                .param("id", job)
                .param("team", team)
                .param("name", "t303-" + job)
                .param("endpoint", endpoint)
                .param("spec", jobSpec)
                .param("actor", "user:" + ids.newId())
                .param("now", Timestamp.from(now))
                .update();
        cell.jdbc()
                .sql(
                        "INSERT INTO scheduler.job_versions(job_id,version,spec,created_at) VALUES (:id,1,CAST(:spec AS jsonb),:now)")
                .param("id", job)
                .param("spec", jobSpec)
                .param("now", Timestamp.from(now))
                .update();

        try (var consumer = new KafkaConsumer<byte[], byte[]>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        KairosKafka.get().bootstrapServers(),
                        ConsumerConfig.GROUP_ID_CONFIG,
                        "t303-watcher-" + UUID.randomUUID(),
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                        "earliest"),
                new ByteArrayDeserializer(),
                new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(KairosTopic.RUN_MEDIUM.topicName()));
            cell.jdbc()
                    .sql("""
                    INSERT INTO scheduler.schedules(schedule_id,target_type,team_id,endpoint_id,shard_id,
                    next_fire_at,kind,dst_policy,misfire_policy,definition_version,priority)
                    VALUES (:id,1,:team,:endpoint,128,:fire,1,1,2,1,5)
                    """)
                    .param("id", job)
                    .param("team", team)
                    .param("endpoint", endpoint)
                    .param("fire", Timestamp.from(Instant.now().plusSeconds(2)))
                    .update();
            Dispatch received = null;
            Instant deadline = Instant.now().plusSeconds(25);
            while (received == null && Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    Dispatch candidate = Dispatch.parseFrom(record.value());
                    if (ProtoIds.toUuid(candidate.getTargetId()).equals(job)) received = candidate;
                }
            }
            assertThat(received).isNotNull();
            assertThat(received.getRequest().getPathParamsMap()).containsEntry("id", "42");
        }
        Awaits.until(() -> cell.jdbc()
                        .sql("SELECT count(*) FROM scheduler.run_inflight WHERE target_id=:job")
                        .param("job", job)
                        .query(Long.class)
                        .single()
                == 1);
        assertThat(cell.jdbc()
                        .sql("SELECT count(*) FROM scheduler.schedules WHERE schedule_id=:job")
                        .param("job", job)
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
