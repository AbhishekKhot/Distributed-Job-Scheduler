package dev.kairos.executor.consume.application;

/** Per-team and per-endpoint concurrent-call limits read from the control database. */
public record DispatchLimits(int teamMaxInflight, int endpointMaxConcurrency) {

    /** Validates positive limits. */
    public DispatchLimits {
        if (teamMaxInflight < 1 || endpointMaxConcurrency < 1) {
            throw new IllegalArgumentException("Dispatch concurrency limits must be positive");
        }
    }
}
