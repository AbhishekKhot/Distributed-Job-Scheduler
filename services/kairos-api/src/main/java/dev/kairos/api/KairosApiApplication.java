package dev.kairos.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of {@code kairos-api}, the public REST API.
 *
 * <p>Serves accounts, teams, API keys, secrets, endpoints, jobs, workflows, runs, the DLQ and
 * stats under {@code /v1}. The caller's team is always taken from the authenticated token or
 * API key. The OpenAPI document is published at {@code /v3/api-docs} and Swagger UI at
 * {@code /swagger-ui.html}.
 */
@SpringBootApplication
public class KairosApiApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(KairosApiApplication.class, args);
    }
}
