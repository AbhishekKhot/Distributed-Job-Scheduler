package dev.kairos.domain.id;

import dev.kairos.domain.shard.Shard;

/**
 * An id that carries its shard in the low 8 bits, so it routes to its cell database on its own.
 */
public sealed interface ShardedId extends TypedId permits JobId, WorkflowId, RunId, WorkflowRunId, DlqId {

    /**
     * Returns the shard embedded in this id.
     *
     * @return the shard
     */
    default Shard shard() {
        return KairosId.shardOf(value());
    }
}
