package dev.kairos.testsupport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Starts the shared test containers and exposes their connection details as Kairos configuration properties.
 *
 * <p>The property names are the ones the Kairos libraries bind (persistence, messaging and the Redis run lock):
 *
 * <table>
 *   <caption>Properties registered for each container</caption>
 *   <tr><th>Container</th><th>Properties</th></tr>
 *   <tr><td>{@link KairosContainer#POSTGRES}</td>
 *       <td>{@code kairos.datasource.control.url|username|password},
 *           {@code kairos.datasource.cells[0].url|username|password},
 *           {@code kairos.datasource.cells[1].url|username|password} (all as {@code sched_app})</td></tr>
 *   <tr><td>{@link KairosContainer#KAFKA}</td><td>{@code kairos.kafka.bootstrap-servers}</td></tr>
 *   <tr><td>{@link KairosContainer#REDIS}</td><td>{@code kairos.redis.host|port|username|password}</td></tr>
 * </table>
 *
 * <p>Spring tests annotated with {@link KairosIntegrationTest} get these properties automatically. A Spring test
 * that does not use the annotation can register them from its own {@code @DynamicPropertySource} method with
 * {@link #springProperties(Set)}.
 *
 * <p>Thread-safe.
 */
public final class KairosContainers {

    /** JDBC URL of the control database. */
    public static final String CONTROL_URL = "kairos.datasource.control.url";

    /** User name for the control database. */
    public static final String CONTROL_USERNAME = "kairos.datasource.control.username";

    /** Password for the control database. */
    public static final String CONTROL_PASSWORD = "kairos.datasource.control.password";

    /** Kafka bootstrap servers. */
    public static final String KAFKA_BOOTSTRAP_SERVERS = "kairos.kafka.bootstrap-servers";

    /** Redis host. */
    public static final String REDIS_HOST = "kairos.redis.host";

    /** Redis port. */
    public static final String REDIS_PORT = "kairos.redis.port";

    /** Redis ACL user name. */
    public static final String REDIS_USERNAME = "kairos.redis.username";

    /** Redis ACL password. */
    public static final String REDIS_PASSWORD = "kairos.redis.password";

    private KairosContainers() {}

    /**
     * Returns the property name of a cell's JDBC URL, for example {@code kairos.datasource.cells[0].url}.
     *
     * @param cell the cell number
     * @return the property name
     */
    public static String cellUrl(int cell) {
        return cellProperty(cell, "url");
    }

    /**
     * Returns the property name of a cell's user name, for example {@code kairos.datasource.cells[0].username}.
     *
     * @param cell the cell number
     * @return the property name
     */
    public static String cellUsername(int cell) {
        return cellProperty(cell, "username");
    }

    /**
     * Returns the property name of a cell's password, for example {@code kairos.datasource.cells[0].password}.
     *
     * @param cell the cell number
     * @return the property name
     */
    public static String cellPassword(int cell) {
        return cellProperty(cell, "password");
    }

    /**
     * Returns the containers an annotation asks for.
     *
     * @param annotation the annotation on the test class
     * @return an unmodifiable set, possibly empty
     */
    public static Set<KairosContainer> selection(KairosIntegrationTest annotation) {
        EnumSet<KairosContainer> selected = EnumSet.noneOf(KairosContainer.class);
        if (annotation.postgres()) {
            selected.add(KairosContainer.POSTGRES);
        }
        if (annotation.kafka()) {
            selected.add(KairosContainer.KAFKA);
        }
        if (annotation.redis()) {
            selected.add(KairosContainer.REDIS);
        }
        return Collections.unmodifiableSet(selected);
    }

    /**
     * Starts the given containers in parallel, if they are not running yet, and waits until all are ready.
     *
     * @param containers the containers to start
     * @throws IllegalStateException if a container cannot be started
     */
    public static void start(Set<KairosContainer> containers) {
        if (containers.size() == 1) {
            containers.iterator().next().start();
            return;
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> starts = new ArrayList<>();
            for (KairosContainer container : containers) {
                starts.add(executor.submit(container::start));
            }
            for (Future<?> start : starts) {
                await(start);
            }
        }
    }

    /**
     * Starts the given containers and returns their connection details under the Kairos property names.
     *
     * @param containers the containers to include
     * @return an unmodifiable map from property name to value, in a stable order
     * @throws IllegalStateException if a container cannot be started
     */
    public static Map<String, String> springProperties(Set<KairosContainer> containers) {
        start(containers);
        Map<String, String> properties = new LinkedHashMap<>();
        if (containers.contains(KairosContainer.POSTGRES)) {
            KairosPostgres postgres = KairosPostgres.get();
            properties.put(CONTROL_URL, postgres.controlJdbcUrl());
            properties.put(CONTROL_USERNAME, postgres.appUsername());
            properties.put(CONTROL_PASSWORD, postgres.appPassword());
            for (int cell = 0; cell < KairosPostgres.CELL_COUNT; cell++) {
                properties.put(cellUrl(cell), postgres.cellJdbcUrl(cell));
                properties.put(cellUsername(cell), postgres.appUsername());
                properties.put(cellPassword(cell), postgres.appPassword());
            }
        }
        if (containers.contains(KairosContainer.KAFKA)) {
            properties.put(KAFKA_BOOTSTRAP_SERVERS, KairosKafka.get().bootstrapServers());
        }
        if (containers.contains(KairosContainer.REDIS)) {
            KairosRedis redis = KairosRedis.get();
            properties.put(REDIS_HOST, redis.host());
            properties.put(REDIS_PORT, Integer.toString(redis.port()));
            properties.put(REDIS_USERNAME, redis.username());
            properties.put(REDIS_PASSWORD, redis.password());
        }
        return Collections.unmodifiableMap(properties);
    }

    private static String cellProperty(int cell, String leaf) {
        if (cell < 0) {
            throw new IllegalArgumentException("Cell must not be negative");
        }
        return "kairos.datasource.cells[" + cell + "]." + leaf;
    }

    private static void await(Future<?> start) {
        try {
            start.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Could not start a test container", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting test containers", e);
        }
    }
}
