package dev.kairos.executor.consume.application;

import dev.kairos.proto.v1.Dispatch;

/** Acquires local concurrency capacity or durably defers the dispatch. */
public interface DispatchAdmission {

    /**
     * Acquires capacity for a dispatch. A false result means the record has already been durably deferred or was
     * stale, so its Kafka offset may be completed.
     *
     * @param dispatch the message
     * @return true when the caller must release capacity after processing
     */
    boolean acquireOrDefer(Dispatch dispatch);

    /** Releases capacity acquired by {@link #acquireOrDefer(Dispatch)}. */
    void release(Dispatch dispatch);
}
