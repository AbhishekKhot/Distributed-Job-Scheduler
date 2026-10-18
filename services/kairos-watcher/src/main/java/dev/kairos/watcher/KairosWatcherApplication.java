package dev.kairos.watcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of {@code kairos-watcher}.
 *
 * <p>Leases shards and, for the shards it owns, fires due schedules and due delayed runs,
 * publishes outbox rows to Kafka, recovers runs whose executor died, and runs the periodic
 * single-instance tasks (auto-suspend, review timeouts, purges).
 */
@SpringBootApplication
public class KairosWatcherApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(KairosWatcherApplication.class, args);
    }
}
