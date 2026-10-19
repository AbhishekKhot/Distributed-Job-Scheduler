/**
 * Identifiers.
 *
 * <p>Every Kairos id is a UUIDv7 ({@link dev.kairos.domain.id.KairosId} documents the bit
 * layout) created by {@link dev.kairos.domain.id.KairosIdFactory} from an injected clock and
 * CSPRNG. Ids of resources stored in cells (jobs, workflows, runs, workflow runs and DLQ entries)
 * carry their shard in the low 8 bits. Typed records ({@link dev.kairos.domain.id.JobId},
 * {@link dev.kairos.domain.id.RunId} and the others) keep ids of different resources apart.
 */
package dev.kairos.domain.id;
