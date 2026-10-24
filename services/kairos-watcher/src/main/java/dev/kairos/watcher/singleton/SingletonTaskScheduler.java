package dev.kairos.watcher.singleton;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs global watcher maintenance only while this process owns the control-database advisory lock. */
public final class SingletonTaskScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(SingletonTaskScheduler.class);
    private final SingletonCoordinator coordinator;
    private final EndpointAutoSuspendEvaluator autoSuspend;
    private final ReviewTimeoutSweeper reviewTimeout;
    private final RetentionPurger purger;

    /** Creates the scheduled singleton task runner. */
    public SingletonTaskScheduler(
            SingletonCoordinator coordinator,
            EndpointAutoSuspendEvaluator autoSuspend,
            ReviewTimeoutSweeper reviewTimeout,
            RetentionPurger purger) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.autoSuspend = Objects.requireNonNull(autoSuspend, "autoSuspend");
        this.reviewTimeout = Objects.requireNonNull(reviewTimeout, "reviewTimeout");
        this.purger = Objects.requireNonNull(purger, "purger");
    }

    /** Evaluates endpoint protection every fifteen seconds. */
    @Scheduled(fixedDelay = 15_000)
    public void autoSuspend() {
        run("auto-suspend", autoSuspend::poll);
    }

    /** Expires blocked workflow reviews every minute. */
    @Scheduled(fixedDelay = 60_000)
    public void reviewTimeout() {
        run("review-timeout", reviewTimeout::poll);
    }

    /** Purges expired and resolved rows hourly. */
    @Scheduled(fixedDelay = 3_600_000)
    public void purge() {
        run("purger", purger::poll);
    }

    private void run(String task, Runnable work) {
        try {
            if (coordinator.isLeader()) work.run();
        } catch (RuntimeException failure) {
            LOG.warn("Singleton task {} failed: {}", task, failure.getClass().getSimpleName());
        }
    }
}
