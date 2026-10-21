package dev.kairos.api.support.etag;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.PreconditionFailedException;
import org.jspecify.annotations.Nullable;

/**
 * Formats and checks the strong entity tags of versioned resources, {@code "<version>"} (LLD section 3.1).
 *
 * <p>Only a single strong tag holding a positive decimal version is accepted in {@code If-Match}. A weak tag, a list,
 * {@code *} or any other value can never equal a current version, so it fails with 412 like a stale one. A missing
 * header fails with 428 {@code if-match-required}. Stateless.
 */
public final class ETags {

    /** LLD message of {@link ErrorCode#IF_MATCH_REQUIRED}. */
    public static final String IF_MATCH_REQUIRED_DETAIL = "Send the current version in the If-Match header.";

    private static final int MAX_LENGTH = 21;

    private ETags() {}

    /**
     * Formats the tag of a version.
     *
     * @param version the resource version, at least 1
     * @return the quoted tag, for example {@code "3"}
     * @throws IllegalArgumentException if the version is not positive
     */
    public static String of(long version) {
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        return "\"" + version + "\"";
    }

    /**
     * Reads the version a client expects from its {@code If-Match} header.
     *
     * @param ifMatch the header value, or {@code null} if absent
     * @return the expected version
     * @throws PreconditionFailedException 428 if the header is missing or blank, 412 if it holds no single strong
     *     version tag
     */
    public static long expectedVersion(@Nullable String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionFailedException(ErrorCode.IF_MATCH_REQUIRED, IF_MATCH_REQUIRED_DETAIL);
        }
        String value = ifMatch.strip();
        if (value.length() < 3 || value.length() > MAX_LENGTH || value.charAt(0) != '"' || !value.endsWith("\"")) {
            throw PreconditionFailedException.versionMismatch();
        }
        long version = 0;
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                throw PreconditionFailedException.versionMismatch();
            }
            version = version * 10 + (c - '0');
            if (version > Integer.MAX_VALUE) {
                throw PreconditionFailedException.versionMismatch();
            }
        }
        if (version < 1) {
            throw PreconditionFailedException.versionMismatch();
        }
        return version;
    }

    /**
     * Checks an expected version against the current one.
     *
     * @param expected the version from {@code If-Match}
     * @param current the stored version
     * @throws PreconditionFailedException 412 if they differ
     */
    public static void requireVersion(long expected, long current) {
        if (expected != current) {
            throw PreconditionFailedException.versionMismatch();
        }
    }
}
