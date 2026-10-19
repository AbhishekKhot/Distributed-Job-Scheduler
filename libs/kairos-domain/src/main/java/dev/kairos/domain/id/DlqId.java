package dev.kairos.domain.id;

import java.util.UUID;

/**
 * Identifier of a dead-letter entry (cell database); carries the shard of the dead-lettered run.
 *
 * @param value a Kairos UUIDv7 with the shard in its low 8 bits
 */
public record DlqId(UUID value) implements ShardedId {

    /**
     * Validates the id.
     *
     * @throws IllegalArgumentException if {@code value} is not a Kairos id
     */
    public DlqId {
        KairosId.requireKairosId(value);
    }

    /**
     * Parses the canonical text form.
     *
     * @param text the text, for example a path parameter
     * @return the id
     * @throws dev.kairos.domain.error.ValidationFailedException on field {@code dlqId} if the text
     *     is not a Kairos id
     */
    public static DlqId parse(String text) {
        return new DlqId(KairosId.parse("dlqId", text));
    }

    /** Returns the canonical UUID text. */
    @Override
    public String toString() {
        return value.toString();
    }
}
