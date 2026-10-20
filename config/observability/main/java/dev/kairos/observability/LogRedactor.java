package dev.kairos.observability;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Masks credentials in text that is about to be logged (IMPLEMENTATION_CHECKLIST section 4.8).
 *
 * <p>Three shapes are masked, case-insensitively:
 *
 * <ul>
 *   <li>a value after a sensitive key, in {@code key=value}, {@code key: value} and JSON
 *       {@code "key" : "value"} form, with any spacing and with escaped quotes (JSON inside a JSON
 *       string). A key is sensitive when it contains {@code password}, {@code passwd}, {@code pwd},
 *       {@code secret}, {@code token}, {@code authorization} or {@code apikey} (also written
 *       {@code api_key}, {@code api-key} or {@code api.key});
 *   <li>a credential of 16 or more token characters after {@code Bearer} or {@code Basic} anywhere
 *       in the text (the length keeps ordinary words such as "basic configuration" intact);
 *   <li>the password in the user-info part of a URL ({@code scheme://user:password@host}).
 * </ul>
 *
 * <p>The patterns use bounded quantifiers only and run on at most {@link #MAX_LENGTH} characters,
 * so matching time is linear in the input and cannot be driven up by a crafted log line. Longer
 * text is truncated first. Redaction is best effort: it is a safety net under the rule that code
 * never logs secrets, not a replacement for it.
 *
 * <p>Instances are stateless; the class is thread-safe.
 */
public final class LogRedactor {

    /** Replacement for every masked value. */
    public static final String MASK = "***";

    /** Longest text that is matched; longer text is truncated before redaction. */
    public static final int MAX_LENGTH = 32_768;

    static final String TRUNCATED = "...[truncated]";

    private static final List<String> SENSITIVE_KEY_WORDS =
            List.of("password", "passwd", "pwd", "secret", "token", "authorization", "apikey");

    private static final String KEY_WORDS = "password|passwd|pwd|secret|token|authorization|api[-_.]?key";

    // Optional quote (possibly escaped), a key containing a sensitive word, the closing quote, and
    // ':' or '=' with any spacing. The opening quote of the value is captured separately so it is
    // kept, and a quoted value may contain spaces while an unquoted one ends at a delimiter.
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?<key>(?:\\\\?[\"'])?[\\w.-]{0,48}(?:" + KEY_WORDS + ")[\\w.-]{0,48}(?:\\\\?[\"'])?"
                    + "\\s{0,16}[:=]\\s{0,16})"
                    + "(?:(?<open>\\\\?[\"'])(?<quoted>[^\"'\\\\\\r\\n]{1,4096})"
                    + "|(?<plain>(?:(?:bearer|basic|digest)\\s{1,16})?[^\\s\"'\\\\,;&}\\]]{1,4096}))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern AUTH_SCHEME =
            Pattern.compile("\\b(?<scheme>bearer|basic)\\s{1,16}[A-Za-z0-9._~+/=-]{16,4096}", Pattern.CASE_INSENSITIVE);

    private static final Pattern URL_USER_INFO = Pattern.compile(
            "(?<prefix>\\b[a-z][a-z0-9+.-]{0,16}://[^/\\s:@]{1,256}:)[^@\\s/]{1,256}@", Pattern.CASE_INSENSITIVE);

    private LogRedactor() {}

    /**
     * Returns {@code text} with every credential masked.
     *
     * @param text the text to redact; {@code null} is returned unchanged
     * @return the redacted text, truncated to {@link #MAX_LENGTH} characters plus a marker when longer
     */
    public static @Nullable String redact(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String bounded = text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) + TRUNCATED : text;
        if (!mayContainCredential(bounded)) {
            return bounded;
        }
        String result = maskKeyValues(bounded);
        result = AUTH_SCHEME
                .matcher(result)
                .replaceAll(match -> Matcher.quoteReplacement(match.group("scheme") + " " + MASK));
        return URL_USER_INFO
                .matcher(result)
                .replaceAll(match -> Matcher.quoteReplacement(match.group("prefix") + MASK + "@"));
    }

    /**
     * Tells whether a JSON member or MDC key names a credential, ignoring case and the separators
     * {@code -}, {@code _} and {@code .} (so {@code X-Api-Key} and {@code refresh_token} match).
     *
     * @param key the key to check
     * @return {@code true} if every value under this key must be masked
     */
    public static boolean isSensitiveKey(@Nullable String key) {
        if (key == null || key.isEmpty() || key.length() > 256) {
            return false;
        }
        String normalised =
                key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(".", "");
        return SENSITIVE_KEY_WORDS.stream().anyMatch(normalised::contains);
    }

    private static String maskKeyValues(String text) {
        return KEY_VALUE.matcher(text).replaceAll(match -> {
            String open = match.group("open");
            String replacement = match.group("key") + (open == null ? "" : open) + MASK;
            return Matcher.quoteReplacement(replacement);
        });
    }

    // A cheap test that lets the common case (no credential-like words) skip the regular expressions.
    private static boolean mayContainCredential(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("://")
                || lower.contains("bearer")
                || lower.contains("basic")
                || lower.contains("pass")
                || lower.contains("pwd")
                || lower.contains("secret")
                || lower.contains("token")
                || lower.contains("authorization")
                || lower.contains("key");
    }
}
