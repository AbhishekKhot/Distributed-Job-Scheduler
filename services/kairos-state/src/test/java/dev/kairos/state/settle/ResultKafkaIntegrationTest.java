package dev.kairos.state.settle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.messaging.client.KafkaClientFactory;
import dev.kairos.messaging.client.KafkaProperties;
import dev.kairos.messaging.client.PoisonMessages;
import dev.kairos.messaging.serde.MessageType;
import dev.kairos.messaging.serde.PoisonReason;
import dev.kairos.messaging.topic.KairosTopic;
import dev.kairos.state.settle.infrastructure.ResultConsumer;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosKafka;
import dev.kairos.testsupport.KairosPostgres;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

@KairosIntegrationTest(redis = false)
class ResultKafkaIntegrationTest {
    @Test
    void databaseCommitPrecedesKafkaOffsetAndRestartReplayIsHarmless(KairosPostgres postgres, KairosKafka kafka)
            throws Exception {
        var f = new SettlementFixtures(postgres);
        UUID run = f.run(f.job(12));
        var result = f.result(run, 1, 0, 200);
        var meters = new SimpleMeterRegistry();
        var properties = new Binder(new MapConfigurationPropertySource(Map.of(
                        "kairos.kafka.bootstrap-servers",
                        kafka.bootstrapServers(),
                        "kairos.kafka.consumer.auto-offset-reset",
                        "earliest")))
                .bind("kairos.kafka", Bindable.of(KafkaProperties.class))
                .get();
        var factory = new KafkaClientFactory(properties, meters);
        String group = "t261-" + UUID.randomUUID();
        var poison = new PoisonMessages(meters);
        var partition = new TopicPartition(KairosTopic.RUN_RESULT.topicName(), 0);
        // Assign one real partition to isolate this test from other result producers in the shared broker.
        long begin;
        try (var client = factory.consumer(group, MessageType.RESULT, 1000)) {
            var consumer = client.consumer();
            consumer.assign(List.of(partition));
            consumer.seekToEnd(List.of(partition));
            begin = consumer.position(partition);
            try (var producer = new org.apache.kafka.clients.producer.KafkaProducer<byte[], byte[]>(
                    Map.of("bootstrap.servers", kafka.bootstrapServers(), "acks", "all"),
                    new org.apache.kafka.common.serialization.ByteArraySerializer(),
                    new org.apache.kafka.common.serialization.ByteArraySerializer())) {
                producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(
                                partition.topic(), 0, new byte[] {1}, new byte[] {(byte) 255}))
                        .get(10, TimeUnit.SECONDS);
                producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(
                                partition.topic(), 0, new byte[] {1}, result.toByteArray()))
                        .get(10, TimeUnit.SECONDS);
            }
            var worker = new ResultConsumer(
                    () -> client,
                    batch -> {
                        f.settler().settle(batch);
                        throw new IllegalStateException("crash after cell commit");
                    },
                    poison,
                    Duration.ofSeconds(1),
                    Duration.ofMillis(1));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            boolean failed = false;
            while (!failed && System.nanoTime() < deadline) {
                try {
                    worker.pollOnce(consumer);
                } catch (IllegalStateException expected) {
                    failed = true;
                }
            }
            assertThat(failed).isTrue();
            assertThat(f.count(run, "run_history")).isEqualTo(1);
            assertThat(consumer.committed(Set.of(partition)).get(partition)).isNull();
            assertThat(consumer.position(partition)).isEqualTo(begin);
        }
        try (var restarted = factory.consumer(group, MessageType.RESULT, 1000)) {
            var consumer = restarted.consumer();
            consumer.assign(List.of(partition));
            consumer.seek(partition, begin);
            var worker = new ResultConsumer(
                    () -> restarted, f.settler(), poison, Duration.ofSeconds(1), Duration.ofMillis(1));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                worker.pollOnce(consumer);
                var offset = consumer.committed(Set.of(partition)).get(partition);
                if (offset != null && offset.offset() >= begin + 2) break;
            }
            assertThat(consumer.committed(Set.of(partition)).get(partition).offset())
                    .isEqualTo(begin + 2);
            assertThat(f.count(run, "run_history")).isEqualTo(1);
            assertThat(f.count(run, "run_inflight")).isZero();
            assertThat(f.observed).hasSize(1);
            assertThat(poison.count(KairosTopic.RUN_RESULT, PoisonReason.MALFORMED))
                    .isGreaterThanOrEqualTo(2);
        }
    }
}
