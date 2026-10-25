package dev.kairos.maintenance.partitions.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/** Runs once for the same UTC day across control and every configured cell; committed work is safe to replay. */
public final class PartitionManager {
    private final Clock clock;
    private final List<PartitionStore> stores;

    public PartitionManager(Clock clock, List<PartitionStore> stores) {
        if (stores.isEmpty()) throw new IllegalArgumentException("At least one partition database is required");
        this.clock = clock;
        this.stores = List.copyOf(stores);
    }

    public List<PartitionStore.Result> run() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        return stores.stream().map(store -> store.maintain(today)).toList();
    }
}
