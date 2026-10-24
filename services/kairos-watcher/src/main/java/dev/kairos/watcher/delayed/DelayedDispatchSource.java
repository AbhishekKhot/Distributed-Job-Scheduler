package dev.kairos.watcher.delayed;

import dev.kairos.persistence.cell.Cell;

/** Supplies the pinned call data required to dispatch a delayed row. */
@FunctionalInterface
public interface DelayedDispatchSource {
    /** Loads the call and current endpoint lifecycle state. */
    DelayedDispatchLoader.LoadedDispatch load(Cell cell, DelayedRun delayed);
}
