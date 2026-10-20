package dev.kairos.domain.id;

import java.util.UUID;

/**
 * Identifier of a workflow run (cell database); carries the shard of its workflow.
 *
 * @param value a Kairos UUIDv7 with the shard in its low 8 bits
 */
public record WorkflowRunId(UUID value) implements ShardedId {

    /**
     * Validates the id.
     *
     * @throws IllegalArgumentException if {@code value} is not a Kairos id
     */
    public WorkflowRunId {
        KairosId.requireKairosId(value);
    }

    /**
     * Parses the canonical text form.
     *
     * @param text the text, for example a path parameter
     * @return the id
     * @throws dev.kairos.domain.error.ValidationFailedException on field {@code workflowRunId} if
     *     the text is not a Kairos id
     */
    public static WorkflowRunId parse(String text) {
        return new WorkflowRunId(KairosId.parse("workflowRunId", text));
    }

    /** Returns the canonical UUID text. */
    @Override
    public String toString() {
        return value.toString();
    }
}
