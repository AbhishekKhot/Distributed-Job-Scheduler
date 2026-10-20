package dev.kairos.http.request;

import java.util.Locale;
import java.util.Objects;

/**
 * One HTTP header line.
 *
 * <p>The name must be an RFC 9110 token of at most 256 characters. The value is at most 8 KB of visible characters,
 * spaces and tabs; CR, LF, NUL and other control characters are refused, so a header can never be split into two
 * (header injection, CWE-113). {@link #toString()} leaves the value out, because header values often carry
 * credentials.
 *
 * @param name the header name
 * @param value the header value
 */
public record HeaderField(String name, String value) {

    /** Longest accepted header name. */
    public static final int MAX_NAME = 256;

    /** Longest accepted header value. */
    public static final int MAX_VALUE = 8192;

    /**
     * Validates the name and value.
     *
     * @throws IllegalArgumentException if the name is not a token or the value has a control character or is too long
     */
    public HeaderField {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (!isToken(name)) {
            throw new IllegalArgumentException(
                    "a header name is an RFC 9110 token of 1 to " + MAX_NAME + " characters");
        }
        if (!isValidValue(value)) {
            throw new IllegalArgumentException(
                    "a header value is at most " + MAX_VALUE + " characters without control characters");
        }
    }

    /**
     * Returns the lower-case name, for case-insensitive comparisons.
     *
     * @return the lower-case name
     */
    public String lowerName() {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Tells whether this header has the given name, ignoring case.
     *
     * @param other the name
     * @return true if the names match
     */
    public boolean hasName(String other) {
        return name.equalsIgnoreCase(other);
    }

    /**
     * Tells whether a string is a valid header value.
     *
     * @param value the candidate value
     * @return true if it is at most 8 KB and has no control character other than horizontal tab
     */
    public static boolean isValidValue(String value) {
        if (value.length() > MAX_VALUE) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f || c > 0xff) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tells whether a string is an RFC 9110 token (valid header name).
     *
     * @param name the candidate name
     * @return true if it is 1 to 256 token characters
     */
    public static boolean isToken(String name) {
        if (name.isEmpty() || name.length() > MAX_NAME) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean alphaNumeric = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!alphaNumeric && "!#$%&'*+-.^_`|~".indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Returns the name only; the value may be a credential. */
    @Override
    public String toString() {
        return "HeaderField[name=" + name + ", value=<redacted>]";
    }
}
