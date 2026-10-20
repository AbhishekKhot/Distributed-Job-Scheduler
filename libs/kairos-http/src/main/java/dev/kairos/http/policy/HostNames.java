package dev.kairos.http.policy;

import java.util.Locale;
import java.util.Optional;

/**
 * Validation of DNS host names.
 *
 * <p>A host is ASCII letters, digits and hyphens in dot-separated labels of 1 to 63 characters, at most 253 in total,
 * with no label starting or ending with a hyphen. A trailing dot is refused because {@code metadata.google.internal.}
 * names the same host as {@code metadata.google.internal} but would slip past exact-name rules. Internationalised names
 * must be given in their ASCII ({@code xn--}) form. Stateless and thread-safe.
 */
public final class HostNames {

    private static final int MAX_NAME = 253;
    private static final int MAX_LABEL = 63;

    private HostNames() {}

    /**
     * Validates a host name and lower-cases it.
     *
     * @param host the host as written
     * @return the lower-case name, or empty if it is not a valid DNS name
     */
    public static Optional<String> normalize(String host) {
        if (host.isEmpty() || host.length() > MAX_NAME) {
            return Optional.empty();
        }
        String name = host.toLowerCase(Locale.ROOT);
        int labelStart = 0;
        for (int i = 0; i <= name.length(); i++) {
            if (i == name.length() || name.charAt(i) == '.') {
                if (!validLabel(name, labelStart, i)) {
                    return Optional.empty();
                }
                labelStart = i + 1;
            } else {
                char c = name.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(name);
    }

    private static boolean validLabel(String name, int start, int end) {
        int length = end - start;
        return length >= 1 && length <= MAX_LABEL && name.charAt(start) != '-' && name.charAt(end - 1) != '-';
    }
}
