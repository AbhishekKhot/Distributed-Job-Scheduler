package dev.kairos.messaging.client;

import dev.kairos.messaging.serde.Decoded;
import dev.kairos.messaging.serde.PoisonReason;
import dev.kairos.messaging.topic.KairosTopic;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Handles records that could not be decoded (IMPLEMENTATION_CHECKLIST section 4.7): each one is counted in
 * {@code kairos_messages_poison_total} (labels {@code topic} and {@code reason}), logged with its topic, partition,
 * offset, reason and size but never its key or payload, and skipped. The consumer then carries on with the next record
 * and commits past the poison one, so it never blocks the partition.
 *
 * <p>The {@code topic} label is the Kairos topic name, or {@code other} for any other topic, so its values stay bounded.
 *
 * <p>Thread-safe.
 */
public final class PoisonMessages {

    /** The poison counter's Micrometer name, exported to Prometheus as {@code kairos_messages_poison_total}. */
    public static final String METRIC = "kairos.messages.poison";

    private static final System.Logger LOG = System.getLogger(PoisonMessages.class.getName());
    private static final String OTHER_TOPIC = "other";

    private final MeterRegistry meterRegistry;

    /**
     * Creates the handler.
     *
     * @param meterRegistry where the counter is registered
     */
    public PoisonMessages(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    /**
     * Returns a record's message, or handles it as poison.
     *
     * @param record the consumed record
     * @param <T> the message class
     * @return the message, or empty if the record was poison (already counted and logged)
     */
    public <T> Optional<T> message(ConsumerRecord<?, Decoded<T>> record) {
        Objects.requireNonNull(record, "record");
        Decoded<T> value = record.value();
        if (value == null) {
            // The deserializer never returns null; a null here means a consumer built without it.
            handle(record.topic(), record.partition(), record.offset(), PoisonReason.EMPTY, 0);
            return Optional.empty();
        }
        return switch (value) {
            case Decoded.Valid<T> valid -> Optional.of(valid.message());
            case Decoded.Poison<T> poison -> {
                handle(record.topic(), record.partition(), record.offset(), poison.reason(), poison.size());
                yield Optional.empty();
            }
        };
    }

    /**
     * Returns how many poison records were counted for a topic and reason.
     *
     * @param topic the topic
     * @param reason the reason
     * @return the count so far
     */
    public double count(KairosTopic topic, PoisonReason reason) {
        return counter(topic.topicName(), reason).count();
    }

    private void handle(String topic, int partition, long offset, PoisonReason reason, int size) {
        String label = KairosTopic.fromName(topic).map(KairosTopic::topicName).orElse(OTHER_TOPIC);
        counter(label, reason).increment();
        LOG.log(
                System.Logger.Level.WARNING,
                "Skipped a poison record: topic={0}, partition={1}, offset={2}, reason={3}, size={4}",
                label,
                partition,
                Long.toString(offset),
                reason.label(),
                size);
    }

    private Counter counter(String topic, PoisonReason reason) {
        return Counter.builder(METRIC)
                .description("Kafka records skipped because they could not be decoded")
                .tag("topic", topic)
                .tag("reason", reason.label())
                .register(meterRegistry);
    }
}
