package dev.kairos.messaging.client;

import dev.kairos.messaging.trace.TraceHeaders;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

/**
 * Publishes batches of records through one idempotent producer and reports each record's outcome separately, so the
 * outbox relay (T-224) deletes exactly the rows Kafka acknowledged and leaves the others for its next cycle.
 *
 * <p>Records of one call are handed to the producer in order, so records with the same key keep their order on the
 * partition (idempotence keeps it across retries). A record the producer refuses synchronously (for example when its
 * buffer stays full past {@code max.block.ms}) fails only its own future. Futures complete within the producer's
 * {@code delivery.timeout.ms}.
 *
 * <p>Thread-safe. Closing it closes the producer and unbinds its metrics.
 */
public final class KafkaPublisher implements AutoCloseable {

    private final Producer<byte[], byte[]> producer;
    private final AutoCloseable metrics;

    /**
     * Wraps a producer.
     *
     * @param producer the producer
     * @param metrics the producer's metrics binding, closed with it
     */
    public KafkaPublisher(Producer<byte[], byte[]> producer, AutoCloseable metrics) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Sends a batch.
     *
     * @param records the records, in order; may be empty
     * @return one future per record, in the same order, completed with the record's partition and offset or with the
     *     reason it was not written
     */
    public List<CompletableFuture<RecordMetadata>> publish(List<OutgoingRecord> records) {
        Objects.requireNonNull(records, "records");
        List<CompletableFuture<RecordMetadata>> futures = new ArrayList<>(records.size());
        for (OutgoingRecord record : records) {
            futures.add(send(record));
        }
        return Collections.unmodifiableList(futures);
    }

    /**
     * Sends one record.
     *
     * @param record the record
     * @return its outcome
     */
    public CompletableFuture<RecordMetadata> publish(OutgoingRecord record) {
        return send(Objects.requireNonNull(record, "record"));
    }

    /**
     * Sends everything buffered now instead of waiting for {@code linger.ms}.
     */
    public void flush() {
        producer.flush();
    }

    /** Waits up to the delivery timeout for buffered records, then closes the producer and unbinds its metrics. */
    @Override
    public void close() {
        try {
            producer.close(Duration.ofSeconds(30));
        } finally {
            try {
                metrics.close();
            } catch (Exception _) {
                // Unbinding meters cannot fail in a way the caller could act on.
            }
        }
    }

    private CompletableFuture<RecordMetadata> send(OutgoingRecord record) {
        CompletableFuture<RecordMetadata> future = new CompletableFuture<>();
        ProducerRecord<byte[], byte[]> producerRecord = new ProducerRecord<>(
                record.topic().topicName(),
                record.key().toByteArray(),
                record.value().toByteArray());
        TraceHeaders.setTraceParent(producerRecord.headers(), record.traceParent());
        try {
            // The callback completes the returned future, which carries any failure; Kafka's own future is redundant.
            var _ = producer.send(producerRecord, (metadata, exception) -> {
                if (exception != null) {
                    future.completeExceptionally(exception);
                } else {
                    future.complete(metadata);
                }
            });
        } catch (RuntimeException e) {
            // A synchronous refusal fails this record only; the rest of the batch is still sent.
            future.completeExceptionally(e);
        }
        return future;
    }
}
