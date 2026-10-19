package dev.kairos.domain.model;

/** Kafka topic of an outbox row ({@code outbox.topic}), with the topic name it is published to. */
public enum OutboxTopic implements CodedEnum {
    /** Dispatches of high-priority runs (priority 8 to 10). */
    RUN_HIGH(1, "kairos.run.high"),
    /** Dispatches of medium-priority runs (priority 5 to 7). */
    RUN_MEDIUM(2, "kairos.run.medium"),
    /** Dispatches of low-priority runs (priority 1 to 4). */
    RUN_LOW(3, "kairos.run.low"),
    /** Cancel requests for running runs, read by every executor. */
    RUN_CANCEL(4, "kairos.run.cancel"),
    /** Completion events for subscribers. */
    RUN_COMPLETED(5, "kairos.run.completed"),
    /** Dead-letter events for alerting. */
    RUN_DEAD(6, "kairos.run.dead");

    private static final CodeIndex<OutboxTopic> INDEX = CodeIndex.of(values());

    private final int code;
    private final String topicName;

    OutboxTopic(int code, String topicName) {
        this.code = code;
        this.topicName = topicName;
    }

    @Override
    public int code() {
        return code;
    }

    /**
     * Returns the Kafka topic name.
     *
     * @return the topic name, for example {@code kairos.run.high}
     */
    public String topicName() {
        return topicName;
    }

    /**
     * Returns the dispatch topic for a priority tier.
     *
     * @param tier the run's priority tier
     * @return {@link #RUN_HIGH}, {@link #RUN_MEDIUM} or {@link #RUN_LOW}
     */
    public static OutboxTopic dispatchTopic(PriorityTier tier) {
        return switch (tier) {
            case HIGH -> RUN_HIGH;
            case MEDIUM -> RUN_MEDIUM;
            case LOW -> RUN_LOW;
        };
    }

    /**
     * Returns the topic for a database code.
     *
     * @param code the code
     * @return the topic
     * @throws IllegalArgumentException if the code is unknown
     */
    public static OutboxTopic fromCode(int code) {
        return INDEX.fromCode(code);
    }
}
