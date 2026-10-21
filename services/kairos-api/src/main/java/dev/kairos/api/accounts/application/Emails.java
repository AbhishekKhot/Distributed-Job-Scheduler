package dev.kairos.api.accounts.application;

import java.util.Locale;
import java.util.Optional;

/**
 * Normalises account emails (LLD section 2.2: unique, lower-case, at most 254 characters).
 *
 * <p>The check is structural, without regular expressions: one {@code @}, a local part of 1 to 64 printable ASCII
 * characters without spaces or the specials of RFC 5322, and a domain of dot-separated labels of letters, digits and
 * hyphens with at least two labels. No DNS lookup and no mail is sent, so the check only keeps obvious garbage out.
 * Stateless and thread-safe.
 */
public final class Emails {

    /** The longest email accepted. */
    public static final int MAX_LENGTH = 254;

    private static final int MAX_LOCAL = 64;
    private static final int MAX_LABEL = 63;
    private static final String LOCAL_SPECIALS = "\"(),:;<>@[\\]";

    private Emails() {}

    /**
     * Normalises an email: lower-cases it and checks its structure.
     *
     * @param email the submitted email
     * @return the normalised email, or empty if it is not a plausible address
     */
    public static Optional<String> normalize(String email) {
        if (email.isEmpty() || email.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        String lower = email.toLowerCase(Locale.ROOT);
        int at = lower.indexOf('@');
        if (at <= 0 || at != lower.lastIndexOf('@') || at > MAX_LOCAL) {
            return Optional.empty();
        }
        String local = lower.substring(0, at);
        String domain = lower.substring(at + 1);
        return validLocal(local) && validDomain(domain) ? Optional.of(lower) : Optional.empty();
    }

    private static boolean validLocal(String local) {
        if (local.startsWith(".") || local.endsWith(".") || local.contains("..")) {
            return false;
        }
        for (int i = 0; i < local.length(); i++) {
            char c = local.charAt(i);
            if (c <= ' ' || c >= 0x7F || LOCAL_SPECIALS.indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean validDomain(String domain) {
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (label.isEmpty() || label.length() > MAX_LABEL || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
                if (!allowed) {
                    return false;
                }
            }
        }
        return true;
    }
}
