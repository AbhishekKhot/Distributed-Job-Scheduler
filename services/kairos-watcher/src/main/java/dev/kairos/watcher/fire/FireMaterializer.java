package dev.kairos.watcher.fire;

import dev.kairos.domain.schedule.Fire;
import dev.kairos.persistence.cell.Cell;
import java.util.List;

/** Creates the durable job or workflow runs and their dispatch outbox rows for a schedule. */
@FunctionalInterface
public interface FireMaterializer {
    /** Runs inside the schedule claim's cell transaction; returns newly inserted execution count. */
    int create(Cell cell, FireSchedule schedule, List<Fire> occurrences);
}
