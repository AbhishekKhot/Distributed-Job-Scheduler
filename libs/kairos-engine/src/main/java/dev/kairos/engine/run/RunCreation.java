package dev.kairos.engine.run;

import java.util.List;
import java.util.Objects;

/**
 * What {@link RunCreator#create} did with the planned runs.
 *
 * @param created the runs that were inserted, in input order; the queued ones got a dispatch row
 * @param duplicates the runs whose occurrence already existed, in input order; nothing was written for them
 */
public record RunCreation(List<InflightRun> created, List<InflightRun> duplicates) {

    /** Copies both lists, so the result is immutable. */
    public RunCreation {
        created = List.copyOf(Objects.requireNonNull(created, "created"));
        duplicates = List.copyOf(Objects.requireNonNull(duplicates, "duplicates"));
    }
}
