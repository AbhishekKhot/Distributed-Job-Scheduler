package dev.kairos.api.support.idempotency;

/** Which database holds the idempotency record of an operation (LLD section 2.2, {@code api_idempotency}). */
public enum IdempotencyScope {
    /**
     * The control database: resources stored there, and operations that span cells (batch job creation, bulk DLQ
     * redrive).
     */
    CONTROL,
    /**
     * The cell of the shard of the team and the body's {@code name} field, the shard a new job or workflow gets
     * (job and workflow creation).
     */
    NAME_SHARD,
    /** The cell of the shard embedded in the path variable {@code id} (run now on a job or workflow). */
    PATH_ID_SHARD
}
