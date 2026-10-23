package dev.kairos.watcher.config;

import dev.kairos.persistence.config.PersistenceConfiguration;
import dev.kairos.watcher.lease.OwnedShards;
import dev.kairos.watcher.lease.ShardLeaseManager;
import dev.kairos.watcher.node.NodeIdentity;
import dev.kairos.watcher.node.NodeRegistry;
import dev.kairos.watcher.support.ShardedPoller;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Explicit Spring wiring for node registration, shard leases and the shared polling base. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "kairos.watcher.enabled", havingValue = "true")
@EnableConfigurationProperties(WatcherProperties.class)
@EnableScheduling
@Import(PersistenceConfiguration.class)
public class WatcherConfiguration {
    /** Creates the configuration; Spring calls it. */
    public WatcherConfiguration() {}

    /** Supplies the UTC process clock used for local lease deadlines. */
    @Bean
    public Clock watcherClock() {
        return Clock.systemUTC();
    }

    /** Builds the stable identity for this watcher process. */
    @Bean
    public NodeIdentity watcherIdentity(
            Clock watcherClock,
            @Value("${HOSTNAME:local}") String pod,
            @Value("${kairos.watcher.version:dev}") String version) {
        return NodeIdentity.watcher(pod, watcherClock.instant(), version);
    }

    /** Connects this process to the shared liveness registry. */
    @Bean
    public NodeRegistry nodeRegistry(
            @Qualifier("controlJdbcClient") JdbcClient control, NodeIdentity identity, WatcherProperties properties) {
        return new NodeRegistry(control, identity, properties.heartbeatStaleAfter());
    }

    /** Holds the per-cell local view of generation-fenced leases. */
    @Bean
    public OwnedShards ownedShards() {
        return new OwnedShards();
    }

    /**
     * Reconciles leases on startup and at the configured heartbeat interval.
     * @param cells configured cell databases
     * @param nodes node heartbeat registry
     * @param owned current lease view
     * @param properties lease timings
     * @param meters metrics registry
     * @return the manager
     */
    @Bean(destroyMethod = "close")
    public ShardLeaseManager shardLeaseManager(
            dev.kairos.persistence.cell.CellRouter cells,
            NodeRegistry nodes,
            OwnedShards owned,
            WatcherProperties properties,
            MeterRegistry meters) {
        return new ShardLeaseManager(
                cells,
                nodes,
                owned,
                properties.leaseTtl(),
                properties.stopBeforeLeaseExpiry(),
                properties.retainedSurplusLeases(),
                meters);
    }

    /** Starts the heartbeat and lease reconciliation loop. */
    @Bean
    public LeaseRefresh leaseRefresh(ShardLeaseManager manager) {
        return new LeaseRefresh(manager);
    }

    /** Provides the shared base used by poller-specific components. */
    @Bean(destroyMethod = "close")
    public ShardedPoller shardedPoller(
            dev.kairos.persistence.cell.CellRouter cells,
            OwnedShards owned,
            NodeIdentity identity,
            Clock watcherClock,
            WatcherProperties properties,
            MeterRegistry meters) {
        return new ShardedPoller(
                cells,
                owned,
                identity.nodeId(),
                watcherClock,
                properties.pollJitter(),
                properties.shardGroupSize(),
                meters);
    }

    /** Spring scheduled adapter kept separate from the lease store. */
    public static final class LeaseRefresh {
        private static final Logger LOG = LoggerFactory.getLogger(LeaseRefresh.class);
        private final ShardLeaseManager manager;

        private LeaseRefresh(ShardLeaseManager manager) {
            this.manager = manager;
        }

        /** Renews leases at the configured cadence. */
        @Scheduled(fixedDelayString = "${kairos.watcher.heartbeat-interval:5s}")
        public void refresh() {
            try {
                manager.reconcile();
            } catch (RuntimeException failure) {
                LOG.warn("Watcher lease refresh failed: {}", failure.getClass().getSimpleName());
            }
        }
    }
}
