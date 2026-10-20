package dev.kairos.security.apikey;

import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The peppers API key hashes are keyed with: the current one, which every new hash uses, and optionally the previous
 * one, kept during a rotation so existing keys keep working until they are re-hashed or expire.
 *
 * <p>Each pepper is 32 random bytes from Secret Manager in production and {@code KAIROS_API_KEY_PEPPER} locally, given
 * as 64 hex characters, and has a version that is stored next to every hash ({@code api_keys.pepper_version}).
 *
 * <p>Immutable and thread-safe. The pepper bytes never appear in {@link #toString()}.
 */
public final class ApiKeyPeppers {

    private static final int PEPPER_BYTES = 32;

    private final int currentVersion;
    private final Map<Integer, byte[]> peppers;

    private ApiKeyPeppers(int currentVersion, Map<Integer, byte[]> peppers) {
        this.currentVersion = currentVersion;
        this.peppers = peppers;
    }

    /**
     * Creates the peppers.
     *
     * @param currentVersion the current pepper's version, 1 to 32767
     * @param currentHex the current pepper, 64 hex characters
     * @param previousVersion the previous pepper's version, or {@code null} if there is none
     * @param previousHex the previous pepper, or {@code null} if there is none
     * @return the peppers
     * @throws IllegalArgumentException if a pepper is not 64 hex characters, a version is out of range, the versions
     *     are equal, or only one of {@code previousVersion} and {@code previousHex} is given
     */
    public static ApiKeyPeppers of(
            int currentVersion, String currentHex, @Nullable Integer previousVersion, @Nullable String previousHex) {
        Map<Integer, byte[]> peppers = new HashMap<>();
        peppers.put(requireVersion(currentVersion), decode(currentHex));
        if ((previousVersion == null) != (previousHex == null)) {
            throw new IllegalArgumentException("the previous pepper needs both a version and a value");
        }
        if (previousVersion != null && previousHex != null) {
            if (previousVersion == currentVersion) {
                throw new IllegalArgumentException("the previous pepper must have another version");
            }
            peppers.put(requireVersion(previousVersion), decode(previousHex));
        }
        return new ApiKeyPeppers(currentVersion, Map.copyOf(peppers));
    }

    /**
     * Returns the version new hashes are written with.
     *
     * @return the current version
     */
    public int currentVersion() {
        return currentVersion;
    }

    byte[] current() {
        return Objects.requireNonNull(peppers.get(currentVersion));
    }

    Optional<byte[]> version(int version) {
        return Optional.ofNullable(peppers.get(version));
    }

    private static int requireVersion(int version) {
        if (version < 1 || version > Short.MAX_VALUE) {
            throw new IllegalArgumentException("pepper versions are 1 to 32767");
        }
        return version;
    }

    private static byte[] decode(String hex) {
        Objects.requireNonNull(hex, "pepper");
        if (hex.length() != PEPPER_BYTES * 2) {
            throw new IllegalArgumentException("a pepper must be 64 hexadecimal characters");
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            // HexFormat's message may quote the input; never pass it on.
            throw new IllegalArgumentException("a pepper must be 64 hexadecimal characters");
        }
    }

    /** Lists the versions only. */
    @Override
    public String toString() {
        return "ApiKeyPeppers[current=" + currentVersion + ", versions=" + peppers.keySet() + "]";
    }
}
