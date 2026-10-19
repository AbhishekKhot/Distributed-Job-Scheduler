package dev.kairos.domain.id;

import java.util.UUID;

/**
 * Identifier of a workflow (cell database); carries the workflow's shard.
 *
 * @param value a Kairos UUIDv7 with the shard in its low 8 bits
 */
public record WorkflowId(UUID value) implements ShardedId {

    /**
     * Validates the id.
     *
     * @throws IllegalArgumentException if {@code value} is not a Kairos id
     */
    public WorkflowId {
        KairosId.requireKairosId(value);
    }

    /**
     * Parses the canonical text form.
     *
     * @param text the text, for example a path parameter
     * @return the id
     * @throws dev.kairos.domain.error.ValidationFailedException on field {@code workflowId} if the
     *     text is not a Kairos id
     */
    public static WorkflowId parse(String text) {
        return new WorkflowId(KairosId.parse("workflowId", text));
    }

    /** Returns the canonical UUID text. */
    @Override
    public String toString() {
        return value.toString();
    }
}
