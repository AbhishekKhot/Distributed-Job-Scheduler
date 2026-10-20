package dev.kairos.domain.id;

import java.util.UUID;

/**
 * Identifier of a user (control database).
 *
 * @param value a Kairos UUIDv7
 */
public record UserId(UUID value) implements TypedId {

    /**
     * Validates the id.
     *
     * @throws IllegalArgumentException if {@code value} is not a Kairos id
     */
    public UserId {
        KairosId.requireKairosId(value);
    }

    /**
     * Parses the canonical text form.
     *
     * @param text the text, for example from a token claim
     * @return the id
     * @throws dev.kairos.domain.error.ValidationFailedException on field {@code userId} if the text
     *     is not a Kairos id
     */
    public static UserId parse(String text) {
        return new UserId(KairosId.parse("userId", text));
    }

    /** Returns the canonical UUID text. */
    @Override
    public String toString() {
        return value.toString();
    }
}
