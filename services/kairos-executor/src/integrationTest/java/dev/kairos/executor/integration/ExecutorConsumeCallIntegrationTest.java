package dev.kairos.executor.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpServer;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.shard.Shard;
import dev.kairos.executor.call.infrastructure.ExecutorExecutionAction;
import dev.kairos.http.client.SendContext;
import dev.kairos.http.request.HttpMethod;
import dev.kairos.messaging.topic.KairosTopic;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.proto.v1.TargetType;
import dev.kairos.security.token.AccessTokenService;
import dev.kairos.testsupport.Awaits;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosKafka;
import dev.kairos.testsupport.KairosPostgres;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Consumes a real Kafka dispatch, claims it in PostgreSQL, and reaches a mock HTTP target. */
@KairosIntegrationTest
@ActiveProfiles("local")
@SpringBootTest(properties = {"management.server.port=0"})
class ExecutorConsumeCallIntegrationTest {
    @MockitoBean(name = "executorInternalAccessTokens")
    private AccessTokenService internalTokens;

    @MockitoBean
    private ExecutorExecutionAction action;

    @Autowired
    private CellRouter cells;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ExecutorSchema.ensure(KairosPostgres.get());
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        registry.add("kairos.security.kek.local-key", () -> HexFormat.of().formatHex(secret));
    }

    @Test
    void dispatchClaimsRunAndCallsMockTarget() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer target = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        target.createContext("/execute", exchange -> {
            calls.incrementAndGet();
            byte[] response = "ok".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        target.start();
        try {
            doAnswer(invocation -> {
                        var observer = (dev.kairos.http.client.SendObserver) invocation.getArgument(1);
                        observer.beforeSend(new SendContext(HttpMethod.POST, "jobs.example.test", 443));
                        try (HttpClient client = HttpClient.newHttpClient()) {
                            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                                            + target.getAddress().getPort() + "/execute"))
                                    .timeout(Duration.ofSeconds(5))
                                    .POST(HttpRequest.BodyPublishers.noBody())
                                    .build();
                            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString())
                                            .statusCode())
                                    .isEqualTo(200);
                        }
                        return null;
                    })
                    .when(action)
                    .execute(any(), any(), any());
            KairosIdFactory ids = new KairosIdFactory(Clock.systemUTC(), new SecureRandom());
            UUID run = ids.newRunId(Shard.of(12)).value();
            UUID job = ids.newJobId(Shard.of(12)).value();
            UUID team = ids.newTeamId().value();
            UUID endpoint = ids.newEndpointId().value();
            Instant now = Instant.now();
            var control = org.springframework.jdbc.core.simple.JdbcClient.create(
                    KairosPostgres.get().controlDataSource());
            control.sql("INSERT INTO scheduler.teams(team_id,slug,created_at,updated_at) VALUES (:id,:slug,:now,:now)")
                    .param("id", team)
                    .param("slug", "t303-" + team.toString().substring(28))
                    .param("now", Timestamp.from(now))
                    .update();
            String spec =
                    "{\"url\":\"https://jobs.example.test/execute\",\"method\":\"POST\",\"timeoutMs\":5000,\"auth\":{\"type\":\"NONE\"}}";
            control.sql(
                            "INSERT INTO scheduler.api_endpoints(endpoint_id,team_id,name,version,spec,created_at,updated_at) VALUES (:id,:team,'target',1,CAST(:spec AS jsonb),:now,:now)")
                    .param("id", endpoint)
                    .param("team", team)
                    .param("spec", spec)
                    .param("now", Timestamp.from(now))
                    .update();
            control.sql(
                            "INSERT INTO scheduler.api_endpoint_versions(endpoint_id,version,spec,created_at,created_by) VALUES (:id,1,CAST(:spec AS jsonb),:now,'user:t303')")
                    .param("id", endpoint)
                    .param("spec", spec)
                    .param("now", Timestamp.from(now))
                    .update();
            var cell = cells.forShard(12);
            cell.jdbc()
                    .sql("""
                    INSERT INTO scheduler.run_inflight(shard_id,run_id,team_id,target_type,definition_id,definition_version,
                    target_id,endpoint_id,endpoint_version,scheduled_at,due_at,state,series_started_at,fence,priority,trigger)
                    VALUES (12,:run,:team,1,:job,1,:job,:endpoint,1,:now,:now,2,:now,1,9,2)
                    """)
                    .param("run", run)
                    .param("team", team)
                    .param("job", job)
                    .param("endpoint", endpoint)
                    .param("now", Timestamp.from(now))
                    .update();
            Dispatch dispatch = Dispatch.newBuilder()
                    .setRunId(bytes(run))
                    .setShard(12)
                    .setFence(1)
                    .setAttempt(1)
                    .setTeamId(bytes(team))
                    .setTargetType(TargetType.TARGET_TYPE_JOB)
                    .setDefinitionId(bytes(job))
                    .setDefinitionVersion(1)
                    .setTargetId(bytes(job))
                    .setEndpointId(bytes(endpoint))
                    .setEndpointVersion(1)
                    .setPriority(9)
                    .setTimeoutMs(5000)
                    .build();
            try (var producer = new KafkaProducer<byte[], byte[]>(
                    Map.of("bootstrap.servers", KairosKafka.get().bootstrapServers(), "acks", "all"),
                    new ByteArraySerializer(),
                    new ByteArraySerializer())) {
                producer.send(new ProducerRecord<>(
                                KairosTopic.RUN_HIGH.topicName(), bytes(job).toByteArray(), dispatch.toByteArray()))
                        .get();
            }
            Awaits.await(Duration.ofSeconds(25)).until(() -> calls.get() == 1);
            assertThat(cell.jdbc()
                            .sql("SELECT state FROM scheduler.run_inflight WHERE run_id=:run")
                            .param("run", run)
                            .query(Integer.class)
                            .single())
                    .isEqualTo(3);
        } finally {
            target.stop(0);
        }
    }

    private static ByteString bytes(UUID id) {
        return ByteString.copyFrom(ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array());
    }
}
