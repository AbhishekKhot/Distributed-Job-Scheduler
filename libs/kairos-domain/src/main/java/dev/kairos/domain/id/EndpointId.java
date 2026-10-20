package dev.kairos.domain.id;

import java.util.UUID;

/**
 * Identifier of a registered API endpoint (control database).
 *
 * @param value a Kairos UUIDv7
 */
public record EndpointId(UUID value) implements TypedId {

    /**
     * Validates the id.
     *
     * @throws IllegalArgumentException if {@code value} is not a Kairos id
     */
    public EndpointId {
        KairosId.requireKairosId(value);
    }

    /**
     * Parses the canonical text form.
     *
     * @param text the text, for example a path parameter
     * @return the id
     * @throws dev.kairos.domain.error.ValidationFailedException on field {@code endpointId} if the
     *     text is not a Kairos id
     */
    public static EndpointId parse(String text) {
        return new EndpointId(KairosId.parse("endpointId", text));
    }

    /** Returns the canonical UUID text. */
    @Override
    public String toString() {
        return value.toString();
    }
}
