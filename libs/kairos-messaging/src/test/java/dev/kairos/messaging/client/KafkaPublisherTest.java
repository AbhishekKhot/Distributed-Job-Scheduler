package dev.kairos.messaging.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import dev.kairos.messaging.MessagingFixtures;
import dev.kairos.messaging.serde.MessageType;
import dev.kairos.messaging.topic.KairosTopic;
import dev.kairos.messaging.trace.TraceHeaders;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;

/** Per-record outcomes of a batch, synchronous refusals, headers and record validation. */
class KafkaPublisherTest {

    private final MockProducer<byte[], byte[]> producer =
            new MockProducer<>(false, null, new ByteArraySerializer(), new ByteArraySerializer());
    private final AtomicBoolean metricsClosed = new AtomicBoolean();
    private final KafkaPublisher publisher = new KafkaPublisher(producer, () -> metricsClosed.set(true));

    @Test
    void reportsEachRecordOfABatchSeparately() {
        List<CompletableFuture<RecordMetadata>> futures = publisher.publish(List.of(
                OutgoingRecord.of(
                        KairosTopic.RUN_CANCEL,
                        UUID.randomUUID(),
                        MessagingFixtures.cancel(),
                        MessagingFixtures.TRACE_PARENT),
                OutgoingRecord.of(KairosTopic.RUN_CANCEL, UUID.randomUUID(), MessagingFixtures.cancel(), "bad")));

        assertThat(producer.completeNext()).isTrue();
        assertThat(producer.errorNext(new TimeoutException("delivery timeout"))).isTrue();

        assertThat(futures.get(0)).isCompleted();
        assertThat(futures.get(1)).isCompletedExceptionally();
        List<ProducerRecord<byte[], byte[]>> sent = producer.history();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).topic()).isEqualTo("kairos.run.cancel");
        assertThat(TraceHeaders.traceParent(sent.get(0).headers())).contains(MessagingFixtures.TRACE_PARENT);
        assertThat(sent.get(1).headers().lastHeader(TraceHeaders.TRACEPARENT)).isNull();
    }

    @Test
    void aSynchronousRefusalFailsOnlyItsRecord() {
        producer.sendException = new IllegalStateException("buffer exhausted");
        CompletableFuture<RecordMetadata> future = publisher.publish(
                OutgoingRecord.of(KairosTopic.RUN_DEAD, UUID.randomUUID(), MessagingFixtures.dead(), null));

        assertThat(future).isCompletedExceptionally();
        assertThat(publisher.publish(List.of())).isEmpty();
    }

    @Test
    void closesTheProducerAndItsMetrics() {
        publisher.flush();
        publisher.close();

        assertThat(producer.closed()).isTrue();
        assertThat(metricsClosed).isTrue();
    }

    @Test
    void recordsAreValidated() {
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> OutgoingRecord.of(KairosTopic.RUN_HIGH, key, MessagingFixtures.cancel(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new OutgoingRecord(KairosTopic.RUN_HIGH, ByteString.EMPTY, ByteString.copyFromUtf8("x"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutgoingRecord(
                        KairosTopic.RUN_HIGH, ByteString.copyFrom(new byte[257]), ByteString.copyFromUtf8("x"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new OutgoingRecord(KairosTopic.RUN_HIGH, ByteString.copyFromUtf8("k"), ByteString.EMPTY, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutgoingRecord(
                        KairosTopic.RUN_HIGH,
                        ByteString.copyFromUtf8("k"),
                        ByteString.copyFrom(new byte[MessageType.MAX_PAYLOAD_BYTES + 1]),
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
