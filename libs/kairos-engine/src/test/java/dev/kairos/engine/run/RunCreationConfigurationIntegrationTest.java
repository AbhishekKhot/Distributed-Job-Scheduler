package dev.kairos.engine.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.model.RunState;
import dev.kairos.engine.EngineFixtures;
import dev.kairos.engine.outbox.CellTransactionGuard;
import dev.kairos.engine.outbox.OutboxConfiguration;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.tx.CellTransactions;
import dev.kairos.testsupport.KairosContainer;
import dev.kairos.testsupport.KairosContainers;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Starts the engine's run creation configuration the way a service imports it, with the database properties the test
 * containers register, and creates a run through the wired beans.
 */
@KairosIntegrationTest(kafka = false, redis = false)
class RunCreationConfigurationIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");
    private static final String COUNT_OUTBOX =
            "SELECT count(*) FROM scheduler.outbox WHERE shard_id = :shardId AND run_id = :runId";

    private final MutableClock clock = MutableClock.at(NOW);
    private ApplicationContextRunner runner;

    @BeforeEach
    void setUp(KairosPostgres postgres) {
        EngineFixtures.ensureSchema(postgres);
        runner = new ApplicationContextRunner()
                .withUserConfiguration(RunCreationConfiguration.class)
                .withBean(Clock.class, () -> clock)
                .withPropertyValues(runnerProperties());
    }

    @Test
    void wiresRunCreationAndTheOutboxOnTheServiceClock() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(KairosIdFactory.class);
            assertThat(context).hasSingleBean(CellTransactionGuard.class);
            assertThat(context).hasSingleBean(OutboxWriter.class);
            assertThat(context).hasSingleBean(RunInserter.class);
            assertThat(context).hasSingleBean(FireNotifier.class);
            assertThat(context).hasSingleBean(CellRouter.class);
            assertThat(context.getBean(RunCreationProperties.class).dispatchHorizon())
                    .isEqualTo(RunFactory.DEFAULT_DISPATCH_HORIZON);

            RunTarget job = EngineFixtures.job(200, 6);
            PlannedRun planned = context.getBean(RunFactory.class).scheduled(job, NOW.plusSeconds(3), Duration.ZERO);
            assertThat(planned.run().state()).isEqualTo(RunState.QUEUED);
            assertThat(planned.run().seriesStartedAt()).isEqualTo(NOW);

            CellTransactions cells = context.getBean(CellTransactions.class);
            RunCreation creation = cells.execute(
                    job.shard(), jdbc -> context.getBean(RunCreator.class).create(planned, null));
            assertThat(creation.created()).containsExactly(planned.run());

            long outboxRows = cells.execute(
                    job.shard(),
                    jdbc -> jdbc.sql(COUNT_OUTBOX)
                            .param("shardId", (short) job.shard().value())
                            .param("runId", planned.run().runId().value())
                            .query(Long.class)
                            .single());
            assertThat(outboxRows).isEqualTo(1);
        });
    }

    @Test
    void appliesTheConfiguredDispatchHorizon() {
        runner.withPropertyValues("kairos.engine.runs.dispatch-horizon=0s").run(context -> {
            assertThat(context).hasNotFailed();
            PlannedRun planned = context.getBean(RunFactory.class)
                    .scheduled(EngineFixtures.job(10, 6), NOW.plusSeconds(3), Duration.ZERO);
            assertThat(planned.run().state()).isEqualTo(RunState.DELAYED);
        });
    }

    @Test
    void rejectsADispatchHorizonOutOfRange() {
        runner.withPropertyValues("kairos.engine.runs.dispatch-horizon=2m")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("kairos.engine.runs.dispatch-horizon=-1s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theOutboxConfigurationStandsAlone() {
        new ApplicationContextRunner()
                .withUserConfiguration(OutboxConfiguration.class)
                .withPropertyValues(runnerProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OutboxWriter.class);
                    assertThat(context).doesNotHaveBean(RunCreator.class);
                });
    }

    @Test
    void propertiesRangeCheckTheHorizon() {
        assertThat(new RunCreationProperties(Duration.ofSeconds(60)).dispatchHorizon())
                .isEqualTo(Duration.ofSeconds(60));
        assertThatThrownBy(() -> new RunCreationProperties(Duration.ofSeconds(61)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunCreationProperties(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String[] runnerProperties() {
        List<String> properties = new ArrayList<>();
        KairosContainers.springProperties(Set.of(KairosContainer.POSTGRES))
                .forEach((name, value) -> properties.add(name + "=" + value));
        return properties.toArray(String[]::new);
    }
}
