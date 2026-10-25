package dev.kairos.state.workflow.infrastructure;

import dev.kairos.persistence.cell.CellRouter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded, cursor-based repair for workflow changes made by recovery and operator transactions. */
public final class WorkflowReconciler {
    private static final System.Logger LOG = System.getLogger(WorkflowReconciler.class.getName());
    private final CellRouter cells;
    private final JdbcWorkflowProgression progression;
    private final Map<Integer, UUID> cursors = new HashMap<>();

    public WorkflowReconciler(CellRouter cells, JdbcWorkflowProgression progression) {
        this.cells = cells;
        this.progression = progression;
    }

    @Scheduled(fixedDelayString = "${kairos.state.workflow.reconcile-interval:1s}")
    public void scheduled() {
        try {
            poll();
        } catch (RuntimeException failure) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Workflow reconciliation will retry: {0}",
                    failure.getClass().getSimpleName());
        }
    }

    public synchronized int poll() {
        int reconciled = 0;
        for (var cell : cells.allCells()) {
            UUID after = cursors.getOrDefault(cell.id(), new UUID(0, 0));
            var candidates = cell.jdbc()
                    .sql(
                            "SELECT workflow_run_id FROM scheduler.workflow_runs WHERE status IN (1,2) AND workflow_run_id>:after ORDER BY workflow_run_id LIMIT 100")
                    .param("after", after)
                    .query(UUID.class)
                    .list();
            if (candidates.isEmpty()) {
                cursors.remove(cell.id());
                continue;
            }
            for (UUID id : candidates) {
                try {
                    boolean changed = cell.transactions().execute(jdbc -> {
                        var locked = jdbc.sql(
                                        "SELECT workflow_run_id FROM scheduler.workflow_runs WHERE workflow_run_id=:id AND status IN (1,2) FOR UPDATE SKIP LOCKED")
                                .param("id", id)
                                .query(UUID.class)
                                .optional();
                        if (locked.isEmpty()) return false;
                        progression.reconcile(cell, id);
                        return true;
                    });
                    if (changed) reconciled++;
                } catch (RuntimeException failure) {
                    LOG.log(
                            System.Logger.Level.WARNING,
                            "Workflow reconciliation will retry: {0}",
                            failure.getClass().getSimpleName());
                }
                cursors.put(cell.id(), id);
            }
        }
        return reconciled;
    }
}
