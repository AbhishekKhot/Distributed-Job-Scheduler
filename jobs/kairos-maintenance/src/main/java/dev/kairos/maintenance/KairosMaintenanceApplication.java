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
                request -> {
                    try (var context = SpringApplication.run(
                            KairosMaintenanceApplication.class,
                            "--kairos.maintenance.partitions.enabled=false",
                            "--kairos.maintenance.commands.enabled=true")) {
                        return context.getBean(dev.kairos.maintenance.commands.application.OperatorService.class)
                                .execute(request);
                    }
                },
                () -> System.console() != null && System.console().isTerminal(),
                System.console() == null
                        ? new java.io.PrintWriter(System.out, true)
                        : System.console().writer(),
                new java.io.PrintWriter(System.err, true));
        System.exit(status);
    }
}
