package dev.kairos.messaging.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import dev.kairos.messaging.MessagingFixtures;
import dev.kairos.messaging.serde.Decoded;
import dev.kairos.messaging.serde.MessageType;
import dev.kairos.messaging.serde.PoisonReason;
import dev.kairos.messaging.topic.KairosTopic;
import dev.kairos.messaging.topic.KeyType;
import dev.kairos.messaging.trace.TraceHeaders;
import dev.kairos.proto.v1.Cancel;
import dev.kairos.proto.v1.Result;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosKafka;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Producers and consumers against the Kafka test container with the real topics: a round trip on every topic, a poison
 * record skipped without stalling its partition, and pause/resume under load.
 */
@KairosIntegrationTest(postgres = false, redis = false)
class MessagingIntegrationTest {

    private static final Duration POLL = Duration.ofMillis(200);
    private static final Duration DEADLINE = Duration.ofSeconds(60);

    private SimpleMeterRegistry registry;
    private KafkaClientFactory factory;
    private KafkaPublisher publisher;

    @BeforeEach
    void setUp(KairosKafka kafka) {
        registry = new SimpleMeterRegistry();
        factory = new KafkaClientFactory(KafkaProperties.plaintext(kafka.bootstrapServers()), registry);
        publisher = factory.publisher("test");
    }

    @AfterEach
    void tearDown() {
        publisher.close();
    }

    @Test
    void everyTopicRoundTripsWithItsKeyAndTraceParent() throws Exception {
        for (KairosTopic topic : KairosTopic.values()) {
            roundTrip(topic, topic.messageType());
        }
        // The producer's client metrics are bound to the registry.
        assertThat(registry.getMeters())
                .anyMatch(meter -> meter.getId().getName().startsWith("kafka.producer."));
    }

    @Test
    void aPoisonRecordIsCountedAndSkippedWithoutStallingThePartition() throws Exception {
        PoisonMessages poison = new PoisonMessages(registry);
        UUID key = UUID.randomUUID();
        Cancel valid = MessagingFixtures.cancel();
        // Same key, so both land on one partition, the poison record first.
        List<CompletableFuture<RecordMetadata>> sent = publisher.publish(List.of(
                new OutgoingRecord(
                        KairosTopic.RUN_CANCEL,
                        ByteString.copyFrom(KeyType.key(key)),
                        ByteString.copyFrom(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff}),
                        null),
                OutgoingRecord.of(KairosTopic.RUN_CANCEL, key, valid, null)));
        RecordMetadata last = sent.getLast().get(30, TimeUnit.SECONDS);
        assertThat(sent.getFirst().get(30, TimeUnit.SECONDS).partition()).isEqualTo(last.partition());

        try (KairosConsumer<Cancel> handle = factory.consumer(group(), MessageType.CANCEL, 50)) {
            Consumer<byte[], Decoded<Cancel>> consumer = handle.consumer();
            consumer.subscribe(List.of(KairosTopic.RUN_CANCEL.topicName()));
            List<Cancel> received = new ArrayList<>();
            long deadline = System.nanoTime() + DEADLINE.toNanos();
            while (received.isEmpty() && System.nanoTime() < deadline) {
                ConsumerRecords<byte[], Decoded<Cancel>> records = consumer.poll(POLL);
                for (ConsumerRecord<byte[], Decoded<Cancel>> record : records) {
                    if (Arrays.equals(record.key(), KeyType.key(key))) {
                        poison.message(record).ifPresent(received::add);
                    }
                }
                consumer.commitSync();
            }

            assertThat(received).containsExactly(valid);
            assertThat(poison.count(KairosTopic.RUN_CANCEL, PoisonReason.MALFORMED))
                    .isEqualTo(1);
            TopicPartition partition = new TopicPartition(KairosTopic.RUN_CANCEL.topicName(), last.partition());
            OffsetAndMetadata committed = consumer.committed(Set.of(partition)).get(partition);
            assertThat(committed).isNotNull();
            assertThat(committed.offset()).isGreaterThan(last.offset());
        }
    }

    @Test
    void pausesAtTheHighWaterMarkAndResumesUnderLoad() throws Exception {
        int total = 2_000;
        int maxPollRecords = 50;
        PartitionFlowControl control = new PartitionFlowControl(200, 50);
        Set<ByteBuffer> keys = new HashSet<>();
        List<OutgoingRecord> batch = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            UUID key = UUID.randomUUID();
            keys.add(ByteBuffer.wrap(KeyType.key(key)));
            batch.add(OutgoingRecord.of(KairosTopic.RUN_RESULT, key, MessagingFixtures.result(), null));
        }
        for (CompletableFuture<RecordMetadata> future : publisher.publish(batch)) {
            future.get(30, TimeUnit.SECONDS);
        }

        try (KairosConsumer<Result> handle = factory.consumer(group(), MessageType.RESULT, maxPollRecords)) {
            Consumer<byte[], Decoded<Result>> consumer = handle.consumer();
            consumer.subscribe(List.of(KairosTopic.RUN_RESULT.topicName()));
            Set<ByteBuffer> received = new HashSet<>();
            List<ConsumerRecord<byte[], Decoded<Result>>> inFlight = new ArrayList<>();
            int pausedPolls = 0;
            int maxInFlight = 0;
            long deadline = System.nanoTime() + DEADLINE.toNanos();
            while (received.size() < total && System.nanoTime() < deadline) {
                boolean paused = control.apply(consumer);
                ConsumerRecords<byte[], Decoded<Result>> records = consumer.poll(POLL);
                if (paused) {
                    // While paused, polls keep the consumer in its group but return nothing.
                    assertThat(records.isEmpty()).isTrue();
                    pausedPolls++;
                    if (pausedPolls % 3 == 0) {
                        // The slow workers finish everything in flight.
                        control.finished(inFlight.size());
                        inFlight.clear();
                    }
                    continue;
                }
                control.started(records.count());
                for (ConsumerRecord<byte[], Decoded<Result>> record : records) {
                    inFlight.add(record);
                    if (record.key() != null && keys.contains(ByteBuffer.wrap(record.key()))) {
                        received.add(ByteBuffer.wrap(record.key()));
                    }
                }
                maxInFlight = Math.max(maxInFlight, control.inFlight());
                consumer.commitSync();
            }

            assertThat(received).hasSize(total);
            assertThat(pausedPolls).isPositive();
            assertThat(maxInFlight).isLessThan(200 + maxPollRecords);
        }
    }

    private <T extends MessageLite> void roundTrip(KairosTopic topic, MessageType<T> type) throws Exception {
        T message = type.type().cast(MessagingFixtures.sample(type));
        UUID key = MessagingFixtures.keyOf(topic, message);
        RecordMetadata metadata = publisher
                .publish(OutgoingRecord.of(topic, key, message, MessagingFixtures.TRACE_PARENT))
                .get(30, TimeUnit.SECONDS);
        assertThat(metadata.topic()).isEqualTo(topic.topicName());

        try (KairosConsumer<T> handle = factory.consumer(group(), type)) {
            Consumer<byte[], Decoded<T>> consumer = handle.consumer();
            TopicPartition partition = new TopicPartition(topic.topicName(), metadata.partition());
            consumer.assign(List.of(partition));
            consumer.seek(partition, metadata.offset());
            Map<String, Object> found = new HashMap<>();
            long deadline = System.nanoTime() + DEADLINE.toNanos();
            while (found.isEmpty() && System.nanoTime() < deadline) {
                for (ConsumerRecord<byte[], Decoded<T>> record : consumer.poll(POLL)) {
                    if (record.offset() == metadata.offset()) {
                        assertThat(record.key()).isEqualTo(KeyType.key(key));
                        assertThat(TraceHeaders.traceParent(record.headers())).contains(MessagingFixtures.TRACE_PARENT);
                        found.put("value", record.value());
                    }
                }
            }
            assertThat(found.get("value")).as(topic.topicName()).isEqualTo(new Decoded.Valid<>(message));
        }
    }

    private static String group() {
        return "test-" + UUID.randomUUID();
    }
}
