package dev.kairos.domain.id;

import java.util.UUID;

/**
 * Identifier of a run (cell database); carries the shard of its job or workflow.
 *
 * <p>The run id is also the {@code Idempotency-Key} sent to the target, so every attempt of the
 * same run carries the same key.
 *
 * @param value a Kairos UUIDv7 with the shard in its low 8 bits
 */
public record RunId(UUID value) implements ShardedId {

    /**
     * Validates the id.
     *
     * @throws IllegalArgumentException if {@code value} is not a Kairos id
     */
    public RunId {
        KairosId.requireKairosId(value);
    }

    /**
     * Parses the canonical text form.
     *
     * @param text the text, for example a path parameter
     * @return the id
     * @throws dev.kairos.domain.error.ValidationFailedException on field {@code runId} if the text
     *     is not a Kairos id
     */
    public static RunId parse(String text) {
        return new RunId(KairosId.parse("runId", text));
    }

    /** Returns the canonical UUID text. */
    @Override
    public String toString() {
        return value.toString();
    }
}
