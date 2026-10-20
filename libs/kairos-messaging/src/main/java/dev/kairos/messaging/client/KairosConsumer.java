package dev.kairos.messaging.client;

import dev.kairos.messaging.serde.Decoded;
import java.time.Duration;
import java.util.Objects;
import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.Consumer;

/**
 * A Kafka consumer of one message type together with its metrics binding.
 *
 * <p>Not thread-safe, like the Kafka consumer itself: one poll thread owns it. Closing it leaves the group (handing the
 * partitions to the other members), closes the consumer and unbinds its metrics.
 *
 * @param <T> the message class
 */
public final class KairosConsumer<T> implements AutoCloseable {

    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);

    private final Consumer<byte[], Decoded<T>> consumer;
    private final AutoCloseable metrics;

    /**
     * Wraps a consumer.
     *
     * @param consumer the consumer
     * @param metrics the consumer's metrics binding, closed with it
     */
    public KairosConsumer(Consumer<byte[], Decoded<T>> consumer, AutoCloseable metrics) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Returns the Kafka consumer, to subscribe, poll, pause, resume and commit.
     *
     * @return the consumer
     */
    public Consumer<byte[], Decoded<T>> consumer() {
        return consumer;
    }

    @Override
    public void close() {
        try {
            consumer.close(CloseOptions.timeout(CLOSE_TIMEOUT));
        } finally {
            try {
                metrics.close();
            } catch (Exception _) {
                // Unbinding meters cannot fail in a way the caller could act on.
            }
        }
    }
}
