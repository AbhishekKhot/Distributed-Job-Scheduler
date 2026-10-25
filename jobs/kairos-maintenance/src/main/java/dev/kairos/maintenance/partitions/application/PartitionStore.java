package dev.kairos.maintenance.partitions.application;

import java.time.LocalDate;

/** One configured database, maintained under a database-local advisory lock. */
@FunctionalInterface
public interface PartitionStore {
    Result maintain(LocalDate today);

    record Result(String database, boolean skipped, int created, long moved, int dropped, long purged) {}
}
