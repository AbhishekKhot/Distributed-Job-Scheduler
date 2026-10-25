package dev.kairos.maintenance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of {@code kairos-maintenance}.
 *
 * <p>Runs daily partition management for history tables and provides the audited operator
 * commands ({@code team set-quota}, {@code user disable}, {@code user reset-password}).
 */
@SpringBootApplication
public class KairosMaintenanceApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        int status = dev.kairos.maintenance.partitions.infrastructure.PartitionCommandLine.execute(
                args,
                () -> {
                    try (var context = SpringApplication.run(KairosMaintenanceApplication.class)) {
                        return context.getBean(dev.kairos.maintenance.partitions.application.PartitionManager.class)
                                .run();
                    }
                },
                new java.io.PrintWriter(System.out, true),
                new java.io.PrintWriter(System.err, true));
        System.exit(status);
    }
}
