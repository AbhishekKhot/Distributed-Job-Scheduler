package dev.kairos.executor.gate.infrastructure;

import dev.kairos.engine.lock.RunLockConfiguration;
import dev.kairos.executor.gate.application.ExecutionGate;
import dev.kairos.executor.gate.application.ExecutionGateChain;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Explicit Redis lock and executor gate wiring. */
@Configuration(proxyBeanMethods = false)
@Import(RunLockConfiguration.class)
public class ExecutionGateConfiguration {

    /** Creates the ordered four-gate chain. */
    @Bean
    public ExecutionGateChain executionGateChain(List<ExecutionGate> gates, MeterRegistry meters) {
        return new ExecutionGateChain(gates, meters);
    }

    /** Scheduler for renewing run locks and PostgreSQL leases during long endpoint calls. */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService executorLeaseScheduler() {
        return Executors.newScheduledThreadPool(
                2,
                Thread.ofPlatform().name("kairos-executor-lease-", 0).daemon().factory());
    }
}
