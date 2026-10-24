package dev.kairos.executor.consume.infrastructure;

import dev.kairos.messaging.topic.KairosTopic;

/** Priority dispatch stream and the corresponding Kafka poll batch size. */
public enum DispatchTier {
    /** Priority 8 to 10. */
    HIGH(KairosTopic.RUN_HIGH, 250, "high"),
    /** Priority 5 to 7. */
    MEDIUM(KairosTopic.RUN_MEDIUM, 150, "medium"),
    /** Priority 1 to 4. */
    LOW(KairosTopic.RUN_LOW, 100, "low");

    private final KairosTopic topic;
    private final int pollSize;
    private final String label;

    DispatchTier(KairosTopic topic, int pollSize, String label) {
        this.topic = topic;
        this.pollSize = pollSize;
        this.label = label;
    }

    /** Returns the topic. */
    public KairosTopic topic() {
        return topic;
    }

    /** Returns the per-poll cap. */
    public int pollSize() {
        return pollSize;
    }

    /** Returns the bounded metric label. */
    public String label() {
        return label;
    }
}
