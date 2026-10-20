package dev.kairos.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a test class that needs the shared Postgres, Kafka or Redis containers.
 *
 * <p>Before the first test of the class runs, the selected containers are started (in parallel, and only once per
 * JVM; later classes reuse them). Their connection details are then available in two ways:
 *
 * <ul>
 *   <li>Plain JUnit tests declare a {@link KairosPostgres}, {@link KairosKafka} or {@link KairosRedis} parameter on a
 *       test method, constructor or lifecycle method, or call the static {@code get()} of those classes.
 *   <li>Spring tests get the Kairos properties listed in {@link KairosContainers} added to the test application
 *       context's environment, so {@code kairos.datasource.*}, {@code kairos.kafka.*} and {@code kairos.redis.*}
 *       bind without any {@code @DynamicPropertySource} method. The test context cache keys on the selection, so
 *       classes with the same selection share one context.
 * </ul>
 *
 * <p>Every container is selected by default; switch off the ones a test does not use to keep it fast. The annotation
 * also tags the class {@code integration}, so a build can include or exclude these tests as a group.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Tag("integration")
@ExtendWith(KairosIntegrationTestExtension.class)
public @interface KairosIntegrationTest {

    /**
     * Whether the test needs {@link KairosPostgres}.
     *
     * @return {@code true} to start Postgres
     */
    boolean postgres() default true;

    /**
     * Whether the test needs {@link KairosKafka}.
     *
     * @return {@code true} to start Kafka
     */
    boolean kafka() default true;

    /**
     * Whether the test needs {@link KairosRedis}.
     *
     * @return {@code true} to start Redis
     */
    boolean redis() default true;
}
