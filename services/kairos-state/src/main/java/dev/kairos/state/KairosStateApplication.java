package dev.kairos.state;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of {@code kairos-state}.
 *
 * <p>Consumes run results and settles each batch in one transaction per cell: history,
 * retries as DELAYED runs, dead letters, workflow progress, health counters and follow-up
 * outbox rows.
 */
@SpringBootApplication
public class KairosStateApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(KairosStateApplication.class, args);
    }
}
