package dev.kairos.domain.model;

/** Dispatch tier of a {@link Priority}; each tier has its own Kafka topic and consumer share. */
public enum PriorityTier {
    /** Priorities 8 to 10. */
    HIGH,
    /** Priorities 5 to 7. */
    MEDIUM,
    /** Priorities 1 to 4. */
    LOW
}
