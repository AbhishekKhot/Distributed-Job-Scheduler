package dev.kairos.state.settle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.messaging.topic.KairosTopic;
import dev.kairos.testsupport.Awaits;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosKafka;
import dev.kairos.testsupport.KairosPostgres;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Starts the state service and settles a real Kafka result into migrated cell history. */
@KairosIntegrationTest(redis = false)
@ActiveProfiles("local")
@SpringBootTest(
        properties = {
            "management.server.port=0",
            "kairos.state.settle.group-id=t303-state-results",
            "kairos.state.settle.audit-interval=1h",
            "kairos.state.workflow.reconcile-interval=1h"
        })
class StateKafkaSettlementIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        SettlementFixtures.ensureSchema(KairosPostgres.get());
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        registry.add("kairos.security.kek.local-key", () -> HexFormat.of().formatHex(secret));
    }

    @Test
    void KafkaSuccessCommitsOneFinalHistoryRow() throws Exception {
        SettlementFixtures fixture = new SettlementFixtures(KairosPostgres.get());
        UUID run = fixture.run(fixture.job(12));
        byte[] result = fixture.result(run, 1, 0, 200).toByteArray();
        try (var producer = new KafkaProducer<byte[], byte[]>(
                Map.of("bootstrap.servers", KairosKafka.get().bootstrapServers(), "acks", "all"),
                new ByteArraySerializer(),
                new ByteArraySerializer())) {
            producer.send(new ProducerRecord<>(
                            KairosTopic.RUN_RESULT.topicName(), run.toString().getBytes(), result))
                    .get(10, TimeUnit.SECONDS);
        }
        Awaits.until(() -> fixture.count(run, "run_history") == 1);
        assertThat(fixture.count(run, "run_inflight")).isZero();
        assertThat(fixture.cell(run)
                        .jdbc()
                        .sql("SELECT final FROM scheduler.run_history WHERE run_id=:run")
                        .param("run", run)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }
}
