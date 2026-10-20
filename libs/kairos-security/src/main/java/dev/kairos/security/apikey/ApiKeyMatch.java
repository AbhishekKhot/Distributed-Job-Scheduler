package dev.kairos.security.apikey;

/** The outcome of {@link ApiKeyService#verify}. */
public enum ApiKeyMatch {
    /** The secret matches under the current pepper. */
    MATCH,
    /** The secret matches under a previous pepper; the caller should store a new hash. */
    MATCH_OUTDATED_PEPPER,
    /** The secret does not match, or its pepper is no longer configured. */
    NO_MATCH;

    /**
     * Tells whether the key is valid.
     *
     * @return {@code true} for {@link #MATCH} and {@link #MATCH_OUTDATED_PEPPER}
     */
    public boolean matches() {
        return this != NO_MATCH;
    }
}
