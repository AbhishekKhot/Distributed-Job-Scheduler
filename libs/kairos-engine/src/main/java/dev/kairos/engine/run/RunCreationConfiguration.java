package dev.kairos.engine.run;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.engine.outbox.CellTransactionGuard;
import dev.kairos.engine.outbox.OutboxConfiguration;
import dev.kairos.engine.outbox.OutboxWriter;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Spring wiring of run creation. Services that create runs (the API, the watcher and the state service) import it
 * explicitly with {@code @Import(RunCreationConfiguration.class)}; it is not an auto-configuration.
 *
 * <p>It imports {@link OutboxConfiguration}, and through it the persistence configuration. Beans: the
 * {@link RunFactory}, the {@link RunInserter}, the {@link FireNotifier} and the {@link RunCreator}. Settings come from
 * {@code kairos.engine.runs.*} ({@link RunCreationProperties}).
 *
 * <p>A {@link Clock} bean is used when the service defines one; otherwise the UTC system clock is used.
 */
@Configuration(proxyBeanMethods = false)
@Import(OutboxConfiguration.class)
@EnableConfigurationProperties(RunCreationProperties.class)
public class RunCreationConfiguration {

    /** Creates the configuration; Spring calls it. */
    public RunCreationConfiguration() {}

    /**
     * Builds the rows of new runs.
     *
     * @param ids the process's id factory
     * @param properties the run creation settings
     * @param clock the service's clock, if any
     * @return the factory
     */
    @Bean
    public RunFactory runFactory(KairosIdFactory ids, RunCreationProperties properties, ObjectProvider<Clock> clock) {
        return new RunFactory(ids, clock.getIfAvailable(Clock::systemUTC), properties.dispatchHorizon());
    }

    /**
     * Inserts runs once per occurrence.
     *
     * @param guard the transaction guard
     * @return the inserter
     */
    @Bean
    public RunInserter runInserter(CellTransactionGuard guard) {
        return new RunInserter(guard);
    }

    /**
     * Wakes the cell's watchers for runs that are already due.
     *
     * @param guard the transaction guard
     * @return the notifier
     */
    @Bean
    public FireNotifier fireNotifier(CellTransactionGuard guard) {
        return new FireNotifier(guard);
    }

    /**
     * Creates runs and their dispatch rows in the caller's cell transaction.
     *
     * @param inserter inserts the runs
     * @param outbox writes the dispatch rows
     * @param notifier wakes the watchers
     * @param clock the service's clock, if any
     * @return the run creator
     */
    @Bean
    public RunCreator runCreator(
            RunInserter inserter, OutboxWriter outbox, FireNotifier notifier, ObjectProvider<Clock> clock) {
        return new RunCreator(inserter, outbox, notifier, clock.getIfAvailable(Clock::systemUTC));
    }
}
