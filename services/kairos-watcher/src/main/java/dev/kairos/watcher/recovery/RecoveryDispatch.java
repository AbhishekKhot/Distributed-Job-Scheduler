package dev.kairos.watcher.recovery;

import dev.kairos.engine.run.InflightRun;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.watcher.delayed.DelayedDispatchLoader;
import dev.kairos.watcher.delayed.DelayedRun;

/** Copies a run with the next infrastructure attempt before its fence is advanced. */
final class RecoveryDispatch {
    private RecoveryDispatch() {}

    static DelayedDispatchLoader.LoadedDispatch load(DelayedDispatchLoader loader, Cell cell, InflightRun run) {
        return loader.load(cell, new DelayedRun(run, 0));
    }

    static InflightRun withInfraAttempt(InflightRun run, int infraAttempt) {
        return new InflightRun(
                run.runId(),
                run.teamId(),
                run.targetType(),
                run.definitionId(),
                run.definitionVersion(),
                run.targetId(),
                run.taskKey(),
                run.endpointId(),
                run.endpointVersion(),
                run.scheduledAt(),
                run.dueAt(),
                run.state(),
                run.attempt(),
                run.seriesStart(),
                run.seriesStartedAt(),
                infraAttempt,
                run.fence(),
                run.remainingDeps(),
                run.priority(),
                run.trigger(),
                run.maxLatenessMs());
    }
}
