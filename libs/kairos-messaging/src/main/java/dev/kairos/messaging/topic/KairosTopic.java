package dev.kairos.messaging.topic;

import dev.kairos.domain.model.OutboxTopic;
import dev.kairos.domain.model.Priority;
import dev.kairos.domain.model.PriorityTier;
import dev.kairos.messaging.serde.MessageType;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The Kafka topics of LLD section 4.2: name, key, message contract, partitions, retention, the {@code outbox.topic}
 * code of topics published by the outbox relay, and the priority tier of the dispatch topics.
 *
 * <p>The outbox codes are not repeated here: each outbox-fed topic refers to its {@link OutboxTopic} constant in
 * {@code kairos-domain}, the same enum the outbox writer stores, and the enum refuses to load if a name differs from
 * that constant's, so the two cannot drift. Only {@code kairos.run.result} has no outbox code, because executors
 * publish results directly.
 */
public enum KairosTopic {
    /** Dispatches of high-priority runs (8 to 10). */
    RUN_HIGH(
            "kairos.run.high",
            OutboxTopic.RUN_HIGH,
            KeyType.TARGET_ID,
            MessageType.DISPATCH,
            24,
            Duration.ofHours(24),
            PriorityTier.HIGH),
    /** Dispatches of medium-priority runs (5 to 7). */
    RUN_MEDIUM(
            "kairos.run.medium",
            OutboxTopic.RUN_MEDIUM,
            KeyType.TARGET_ID,
            MessageType.DISPATCH,
            48,
            Duration.ofHours(24),
            PriorityTier.MEDIUM),
    /** Dispatches of low-priority runs (1 to 4). */
    RUN_LOW(
            "kairos.run.low",
            OutboxTopic.RUN_LOW,
            KeyType.TARGET_ID,
            MessageType.DISPATCH,
            24,
            Duration.ofHours(24),
            PriorityTier.LOW),
    /** Call results from the executors to {@code kairos-state}. */
    RUN_RESULT("kairos.run.result", null, KeyType.TARGET_ID, MessageType.RESULT, 48, Duration.ofHours(24), null),
    /** Cancel requests, read by every executor. */
    RUN_CANCEL(
            "kairos.run.cancel",
            OutboxTopic.RUN_CANCEL,
            KeyType.RUN_ID,
            MessageType.CANCEL,
            6,
            Duration.ofHours(1),
            null),
    /** Completion events for subscribers. */
    RUN_COMPLETED(
            "kairos.run.completed",
            OutboxTopic.RUN_COMPLETED,
            KeyType.DEFINITION_ID,
            MessageType.COMPLETED,
            12,
            Duration.ofDays(3),
            null),
    /** Dead-letter events for alerting. */
    RUN_DEAD(
            "kairos.run.dead",
            OutboxTopic.RUN_DEAD,
            KeyType.DEFINITION_ID,
            MessageType.DEAD,
            6,
            Duration.ofDays(14),
            null);

    private final String topicName;
    private final @Nullable OutboxTopic outboxTopic;
    private final KeyType keyType;
    // MessageType is deeply immutable (a class and two stateless serde objects) but is not annotated for Error Prone.
    @SuppressWarnings("ImmutableEnumChecker")
    private final MessageType<?> messageType;

    private final int partitions;
    private final Duration retention;
    private final @Nullable PriorityTier tier;

    KairosTopic(
            String topicName,
            @Nullable OutboxTopic outboxTopic,
            KeyType keyType,
            MessageType<?> messageType,
            int partitions,
            Duration retention,
            @Nullable PriorityTier tier) {
        if (outboxTopic != null && !outboxTopic.topicName().equals(topicName)) {
            throw new IllegalStateException(topicName + " does not match the outbox topic " + outboxTopic);
        }
        this.topicName = topicName;
        this.outboxTopic = outboxTopic;
        this.keyType = keyType;
        this.messageType = messageType;
        this.partitions = partitions;
        this.retention = retention;
        this.tier = tier;
    }

    /**
     * Returns the Kafka topic name.
     *
     * @return the name, for example {@code kairos.run.high}
     */
    public String topicName() {
        return topicName;
    }

    /**
     * Returns the outbox topic that is published to this topic.
     *
     * @return the outbox topic, or empty for {@link #RUN_RESULT}
     */
    public Optional<OutboxTopic> outboxTopic() {
        return Optional.ofNullable(outboxTopic);
    }

    /**
     * Returns what the record key is.
     *
     * @return the key type
     */
    public KeyType keyType() {
        return keyType;
    }

    /**
     * Returns the message contract of the record values.
     *
     * @return the message type
     */
    public MessageType<?> messageType() {
        return messageType;
    }

    /**
     * Returns the production partition count.
     *
     * @return the partitions
     */
    public int partitions() {
        return partitions;
    }

    /**
     * Returns the retention.
     *
     * @return the retention
     */
    public Duration retention() {
        return retention;
    }

    /**
     * Returns the priority tier of a dispatch topic.
     *
     * @return the tier, or empty for topics that are not dispatch topics
     */
    public Optional<PriorityTier> tier() {
        return Optional.ofNullable(tier);
    }

    /**
     * Returns the dispatch topic of a priority tier.
     *
     * @param tier the tier
     * @return {@link #RUN_HIGH}, {@link #RUN_MEDIUM} or {@link #RUN_LOW}
     */
    public static KairosTopic dispatch(PriorityTier tier) {
        return forOutbox(OutboxTopic.dispatchTopic(Objects.requireNonNull(tier, "tier")));
    }

    /**
     * Returns the dispatch topic of a run priority.
     *
     * @param priority the run's priority
     * @return the dispatch topic of its tier
     */
    public static KairosTopic dispatch(Priority priority) {
        return dispatch(Objects.requireNonNull(priority, "priority").tier());
    }

    /**
     * Returns the topic an outbox row is published to.
     *
     * @param outboxTopic the row's topic
     * @return the Kafka topic
     */
    public static KairosTopic forOutbox(OutboxTopic outboxTopic) {
        Objects.requireNonNull(outboxTopic, "outboxTopic");
        return Arrays.stream(values())
                .filter(topic -> topic.outboxTopic == outboxTopic)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No Kafka topic for " + outboxTopic));
    }

    /**
     * Returns the topic of a Kafka topic name.
     *
     * @param topicName the name
     * @return the topic, or empty if it is not a Kairos topic
     */
    public static Optional<KairosTopic> fromName(String topicName) {
        Objects.requireNonNull(topicName, "topicName");
        return Arrays.stream(values())
                .filter(topic -> topic.topicName.equals(topicName))
                .findFirst();
    }
}
