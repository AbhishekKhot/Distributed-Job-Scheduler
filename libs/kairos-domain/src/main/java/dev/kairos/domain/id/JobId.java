package dev.kairos.domain.id;

import java.util.UUID;

/**
 * Identifier of a job (cell database); carries the job's shard.
 *
 * @param value a Kairos UUIDv7 with the shard in its low 8 bits
 */
public record JobId(UUID value) implements ShardedId {

    /**
     * Validates the id.
     *
     * @throws IllegalArgumentException if {@code value} is not a Kairos id
     */
    public JobId {
        KairosId.requireKairosId(value);
    }

    /**
     * Parses the canonical text form.
     *
     * @param text the text, for example a path parameter
     * @return the id
     * @throws dev.kairos.domain.error.ValidationFailedException on field {@code jobId} if the text
     *     is not a Kairos id
     */
    public static JobId parse(String text) {
        return new JobId(KairosId.parse("jobId", text));
    }

    /** Returns the canonical UUID text. */
    @Override
    public String toString() {
        return value.toString();
    }
}
