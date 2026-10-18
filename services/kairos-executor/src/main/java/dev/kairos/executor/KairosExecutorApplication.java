package dev.kairos.executor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of {@code kairos-executor}.
 *
 * <p>Consumes dispatch topics, takes the Redis run lock, claims runs with a fence check,
 * renders and sends HTTPS requests to team endpoints, classifies the outcome and publishes
 * results. Never retries on its own; retries are durable timers owned by kairos-state.
 */
@SpringBootApplication
public class KairosExecutorApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(KairosExecutorApplication.class, args);
    }
}
