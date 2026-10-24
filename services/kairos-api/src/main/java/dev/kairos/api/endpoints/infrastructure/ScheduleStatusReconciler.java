package dev.kairos.api.endpoints.infrastructure;

import dev.kairos.api.endpoints.application.EndpointScheduleControl;
import dev.kairos.persistence.cell.CellRouter;
import java.util.Objects;
import java.util.UUID;

/** Applies endpoint suspend/resume effects to every cell after the control status commits. */
public final class ScheduleStatusReconciler implements EndpointScheduleControl {
    private final CellRouter cells;

    public ScheduleStatusReconciler(CellRouter cells) {
        this.cells = Objects.requireNonNull(cells, "cells");
    }

    @Override
    public void reconcile(UUID endpoint, boolean suspended) {
        short from = suspended ? (short) 1 : (short) 3;
        short to = suspended ? (short) 3 : (short) 1;
        for (var cell : cells.allCells()) {
            cell.database().transactions().execute(jdbc -> {
                cell.jdbc()
                        .sql("""
                        UPDATE scheduler.schedules SET status = :to
                         WHERE endpoint_id = :endpoint AND status = :from
                        """)
                        .param("to", to)
                        .param("endpoint", endpoint)
                        .param("from", from)
                        .update();
                return null;
            });
        }
    }
}
