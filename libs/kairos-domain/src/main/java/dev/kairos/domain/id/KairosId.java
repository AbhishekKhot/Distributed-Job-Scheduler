package dev.kairos.domain.id;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.shard.Shard;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Layout of Kairos identifiers: UUIDv7 (RFC 9562) with the shard in the low 8 bits.
 *
 * <pre>
 *  bits 127..80  unix_ts_ms   milliseconds since the epoch (sorts ids by creation time)
 *  bits  79..76  version      7
 *  bits  75..64  sequence     monotonic counter within one millisecond (see {@link KairosIdFactory})
 *  bits  63..62  variant      0b10
 *  bits  61..8   random       54 bits from a CSPRNG
 *  bits   7..0   shard        0 to 255 for job, workflow, run, workflow-run and DLQ ids; random otherwise
 * </pre>
 *
 * <p>Because the shard sits in the random part, ids still sort by creation time and keep at
 * least 54 bits of randomness. This class only reads and validates ids; {@link KairosIdFactory}
 * creates them.
 */
public final class KairosId {

    /** UUID version used by every Kairos id. */
    public static final int VERSION = 7;

    /** Length of the canonical text form, for example {@code 0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e}. */
    public static final int TEXT_LENGTH = 36;

    private static final int IETF_VARIANT = 2;
    private static final int SHARD_MASK = 0xFF;
    private static final int TIMESTAMP_SHIFT = 16;

    private KairosId() {}

    /**
     * Tells whether a UUID has the Kairos layout (version 7, IETF variant).
     *
     * @param id the UUID
     * @return true if it is a Kairos id
     */
    public static boolean isKairosId(UUID id) {
        return id.version() == VERSION && id.variant() == IETF_VARIANT;
    }

    /**
     * Returns the shard embedded in the low 8 bits of a sharded id.
     *
     * @param id a job, workflow, run, workflow-run or DLQ id
     * @return the shard
     * @throws IllegalArgumentException if {@code id} is not a Kairos id
     */
    public static Shard shardOf(UUID id) {
        requireKairosId(id);
        return Shard.of((int) (id.getLeastSignificantBits() & SHARD_MASK));
    }

    /**
     * Returns the creation time encoded in an id, to the millisecond.
     *
     * @param id a Kairos id
     * @return the creation instant
     * @throws IllegalArgumentException if {@code id} is not a Kairos id
     */
    public static Instant timestampOf(UUID id) {
        requireKairosId(id);
        return Instant.ofEpochMilli(id.getMostSignificantBits() >>> TIMESTAMP_SHIFT);
    }

    /**
     * Checks that a UUID is a Kairos id.
     *
     * @param id the UUID
     * @return {@code id}
     * @throws IllegalArgumentException if {@code id} is not version 7 with the IETF variant
     */
    public static UUID requireKairosId(UUID id) {
        Objects.requireNonNull(id, "id");
        if (!isKairosId(id)) {
            throw new IllegalArgumentException("Not a Kairos id (UUIDv7 expected)");
        }
        return id;
    }

    /**
     * Parses the canonical text form of a Kairos id.
     *
     * <p>Input is checked for length and characters before it is parsed, and only the hyphenated
     * 36-character form of a version 7 UUID is accepted (either letter case). Failures never echo
     * the input.
     *
     * @param field the request field the text came from, used in the error
     * @param text the text to parse
     * @return the id
     * @throws ValidationFailedException with code {@link FieldError#FORMAT} if the text is not a
     *     Kairos id
     */
    public static UUID parse(String field, String text) {
        if (text.length() != TEXT_LENGTH || !hasCanonicalShape(text)) {
            throw ValidationFailedException.of(field, FieldError.FORMAT);
        }
        UUID id = UUID.fromString(text);
        if (!isKairosId(id)) {
            throw ValidationFailedException.of(field, FieldError.FORMAT);
        }
        return id;
    }

    private static boolean hasCanonicalShape(String text) {
        for (int i = 0; i < TEXT_LENGTH; i++) {
            char c = text.charAt(i);
            boolean hyphenPosition = i == 8 || i == 13 || i == 18 || i == 23;
            if (hyphenPosition ? c != '-' : !isHexDigit(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
