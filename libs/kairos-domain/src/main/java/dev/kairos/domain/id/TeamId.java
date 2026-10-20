package dev.kairos.domain.id;

import java.util.UUID;

/**
 * Identifier of a team (control database).
 *
 * @param value a Kairos UUIDv7
 */
public record TeamId(UUID value) implements TypedId {

    /**
     * Validates the id.
     *
     * @throws IllegalArgumentException if {@code value} is not a Kairos id
     */
    public TeamId {
        KairosId.requireKairosId(value);
    }

    /**
     * Parses the canonical text form.
     *
     * @param text the text, for example from a token claim
     * @return the id
     * @throws dev.kairos.domain.error.ValidationFailedException on field {@code teamId} if the text
     *     is not a Kairos id
     */
    public static TeamId parse(String text) {
        return new TeamId(KairosId.parse("teamId", text));
    }

    /** Returns the canonical UUID text. */
    @Override
    public String toString() {
        return value.toString();
    }
}
