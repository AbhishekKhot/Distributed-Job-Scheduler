package dev.kairos.domain.model;

/**
 * An enumeration stored in a {@code smallint} column.
 *
 * <p>Codes are part of the storage format: a code is never changed or reused, and new constants
 * take the next free number. Every implementation also offers a static {@code fromCode(int)}
 * that throws on an unknown code, built with {@link CodeIndex}.
 */
public interface CodedEnum {

    /**
     * Returns the stable database code.
     *
     * @return the code, at least 1
     */
    int code();
}
