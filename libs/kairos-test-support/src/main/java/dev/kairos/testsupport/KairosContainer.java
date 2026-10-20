package dev.kairos.testsupport;

/** The infrastructure containers an integration test can ask for. */
public enum KairosContainer {
    /** {@link KairosPostgres}: control database and two cell databases. */
    POSTGRES,
    /** {@link KairosKafka}: a single broker with every {@code kairos.run.*} topic. */
    KAFKA,
    /** {@link KairosRedis}: Redis with the {@code kairos} ACL user. */
    REDIS;

    /** Returns the shared container, starting it if it is not running yet. */
    Object instance() {
        return switch (this) {
            case POSTGRES -> KairosPostgres.get();
            case KAFKA -> KairosKafka.get();
            case REDIS -> KairosRedis.get();
        };
    }

    /** Starts this container if it is not running yet; returns once it is ready. */
    void start() {
        instance();
    }
}
