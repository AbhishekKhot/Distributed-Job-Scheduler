/**
 * Shard routing.
 *
 * <p>{@link dev.kairos.domain.shard.Shard} is one of the 256 fixed shards, and
 * {@link dev.kairos.domain.shard.ShardKey} derives a job's or workflow's shard from its team and
 * immutable name. Cells own contiguous shard ranges; the mapping from shard to cell lives in the
 * persistence layer.
 */
package dev.kairos.domain.shard;
