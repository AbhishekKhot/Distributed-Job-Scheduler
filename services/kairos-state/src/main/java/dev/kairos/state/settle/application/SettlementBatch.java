package dev.kairos.state.settle.application;

import dev.kairos.domain.model.Outcome;
import dev.kairos.engine.run.InflightRun;
import dev.kairos.proto.v1.Result;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Settles a batch in independent cell transactions; returns only effects that committed. */
@FunctionalInterface
public interface SettlementBatch {
    void settle(List<Result> results);
    /** T-263 receives each accepted result once after its cell commit; duplicates produce no callback. */
    @FunctionalInterface
    interface Observer {
        void committed(List<Settled> results);
    }

    record Settled(
            InflightRun run, Result result, @Nullable Outcome outcome, boolean deferred, boolean retryableFailure) {}
}
