package dev.kairos.engine.outbox;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.config.PersistenceConfiguration;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Spring wiring of the transactional outbox writer. Services that write outbox rows import it explicitly with
 * {@code @Import(OutboxConfiguration.class)}, or get it through {@code RunCreationConfiguration}, which imports it; it
 * is not an auto-configuration.
 *
 * <p>It imports {@link PersistenceConfiguration}, whose {@link CellRouter} the guard needs. Beans: the
 * {@link CellTransactionGuard}, the {@link OutboxWriter} and the process's single {@link KairosIdFactory}. There is one
 * id factory per process so ids stay strictly increasing; services inject this bean rather than creating their own.
 *
 * <p>A {@link Clock} bean is used when the service defines one; otherwise the UTC system clock is used.
 */
@Configuration(proxyBeanMethods = false)
@Import(PersistenceConfiguration.class)
public class OutboxConfiguration {

    /** Creates the configuration; Spring calls it. */
    public OutboxConfiguration() {}

    /**
     * The process's id factory, seeded from a {@link SecureRandom}.
     *
     * @param clock the service's clock, if any
     * @return the id factory
     */
    @Bean
    public KairosIdFactory kairosIdFactory(ObjectProvider<Clock> clock) {
        return new KairosIdFactory(clock.getIfAvailable(Clock::systemUTC), new SecureRandom());
    }

    /**
     * Checks that the shard's cell transaction is open before rows are written.
     *
     * @param router the cell router
     * @return the guard
     */
    @Bean
    public CellTransactionGuard cellTransactionGuard(CellRouter router) {
        return new CellTransactionGuard(router);
    }

    /**
     * Writes outbox rows into the caller's cell transaction.
     *
     * @param guard the transaction guard
     * @param ids the process's id factory
     * @param clock the service's clock, if any
     * @return the writer
     */
    @Bean
    public OutboxWriter outboxWriter(CellTransactionGuard guard, KairosIdFactory ids, ObjectProvider<Clock> clock) {
        return new OutboxWriter(guard, ids, clock.getIfAvailable(Clock::systemUTC));
    }
}
