/**
 * Shared test fixtures for every Kairos module.
 *
 * <p>Add the module as a test dependency only:
 *
 * {@snippet lang="kotlin" :
 * dependencies {
 *     testImplementation(project(":libs:kairos-test-support"))
 * }
 * }
 *
 * <p>What it provides:
 *
 * <ul>
 *   <li>{@link dev.kairos.testsupport.KairosPostgres}, {@link dev.kairos.testsupport.KairosKafka} and
 *       {@link dev.kairos.testsupport.KairosRedis}: Testcontainers definitions that use the same image digests and
 *       the same init scripts as {@code deploy/compose}, with credentials generated for every run. Each is started
 *       lazily once per JVM and shared by all test classes of the run.
 *   <li>{@link dev.kairos.testsupport.KairosIntegrationTest}: starts the containers a test class selects and makes
 *       their connection details available to JUnit parameters and to Spring tests (as the properties listed in
 *       {@link dev.kairos.testsupport.KairosContainers}).
 *   <li>{@link dev.kairos.testsupport.MutableClock} to control time, and {@link dev.kairos.testsupport.Awaits} to wait
 *       for asynchronous outcomes without sleeping.
 * </ul>
 *
 * <p>A minimal plain JUnit integration test against cell 0, as the application role:
 *
 * {@snippet :
 * @KairosIntegrationTest(kafka = false, redis = false)
 * class CellSmokeIntegrationTest {
 *
 *     @Test
 *     void connectsAsTheApplicationRole(KairosPostgres postgres) throws SQLException {
 *         try (Connection connection = postgres.cellDataSource(0).getConnection();
 *                 PreparedStatement statement = connection.prepareStatement("SELECT current_user");
 *                 ResultSet result = statement.executeQuery()) {
 *             assertThat(result.next()).isTrue();
 *             assertThat(result.getString(1)).isEqualTo(KairosPostgres.APP_USERNAME);
 *         }
 *     }
 * }
 * }
 *
 * <p>The same in a Spring test: {@code kairos.datasource.*} is already set, so no {@code @DynamicPropertySource}
 * method is needed.
 *
 * {@snippet :
 * @KairosIntegrationTest(kafka = false, redis = false)
 * @SpringBootTest
 * class RunRepositoryIntegrationTest {
 *
 *     @Autowired
 *     RunRepository runs;
 *
 *     @Test
 *     void findsNothingInAnEmptyCell() {
 *         assertThat(runs.findDue(0, 10)).isEmpty();
 *     }
 * }
 * }
 */
@NullMarked
package dev.kairos.testsupport;

import org.jspecify.annotations.NullMarked;
