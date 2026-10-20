package dev.kairos.http.redact;

import dev.kairos.http.request.HeaderField;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Removes secrets from what Kairos stores or shows about a call: headers, error messages and response snippets
 * ({@code result} messages, LLD section 4.2: redacted error at most 512 characters, snippet at most 1 KB).
 *
 * <p>Header values are replaced for {@code Authorization}, {@code Proxy-Authorization}, {@code Cookie},
 * {@code Set-Cookie} and every configured secret header (for example an endpoint's API key header). In text, every
 * known secret value (credentials and outputs marked sensitive) is replaced, and anything shaped like a
 * {@code Bearer}/{@code Basic} credential is masked; secrets are removed before the text is truncated, so a cut can
 * never leave part of a secret behind. Control characters are replaced with spaces.
 *
 * <p>Immutable and thread-safe.
 */
public final class Redactor {

    /** The replacement for a removed value. */
    public static final String REDACTED = "<redacted>";

    /** Longest redacted error message. */
    public static final int MAX_ERROR_CHARS = 512;

    /** Longest redacted response snippet, in UTF-8 bytes. */
    public static final int MAX_SNIPPET_BYTES = 1024;

    /** Text longer than this is cut before redaction; far above both output limits. */
    private static final int MAX_INPUT_CHARS = 64 * 1024;

    private static final Set<String> ALWAYS_SECRET =
            Set.of("authorization", "proxy-authorization", "cookie", "set-cookie");

    /** A bounded, non-nested pattern (linear time on any input); 8+ characters, so prose such as "basic auth" stays. */
    private static final Pattern CREDENTIAL = Pattern.compile("(?i)\\b(bearer|basic)(\\s+)[A-Za-z0-9._~+/=-]{8,8192}");

    private final Set<String> secretHeaders;

    /**
     * Creates a redactor.
     *
     * @param extraSecretHeaders header names to redact besides the built-in ones, any case
     */
    public Redactor(Collection<String> extraSecretHeaders) {
        Set<String> names = new TreeSet<>(ALWAYS_SECRET);
        for (String name : extraSecretHeaders) {
            names.add(name.toLowerCase(Locale.ROOT));
        }
        this.secretHeaders = Set.copyOf(names);
    }

    /**
     * Tells whether a header's value is always redacted.
     *
     * @param name the header name, any case
     * @return true if it is a secret header
     */
    public boolean isSecretHeader(String name) {
        return secretHeaders.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the headers with secret header values replaced.
     *
     * @param headers the headers
     * @param extraSecretHeaders more header names to redact for this call (for example the API key header)
     * @return the redacted headers
     */
    public List<HeaderField> redactHeaders(List<HeaderField> headers, Collection<String> extraSecretHeaders) {
        List<HeaderField> redacted = new ArrayList<>(headers.size());
        for (HeaderField header : headers) {
            boolean secret =
                    isSecretHeader(header.name()) || extraSecretHeaders.stream().anyMatch(header::hasName);
            redacted.add(secret ? new HeaderField(header.name(), REDACTED) : header);
        }
        return List.copyOf(redacted);
    }

    /**
     * Redacts an error message: secrets removed, control characters replaced, at most 512 characters.
     *
     * @param message the message
     * @param secrets secret values to remove (credentials, sensitive outputs)
     * @return the redacted message
     */
    public String redactError(String message, Collection<String> secrets) {
        String text = redactText(message, secrets);
        return text.length() <= MAX_ERROR_CHARS ? text : text.substring(0, cutPoint(text, MAX_ERROR_CHARS));
    }

    /**
     * Redacts a response snippet: the body decoded as UTF-8, secrets removed, control characters replaced, at most
     * 1 KB of UTF-8.
     *
     * @param body the response body (only the first 64 KB are looked at)
     * @param secrets secret values to remove (credentials, sensitive outputs)
     * @return the redacted snippet
     */
    public String snippet(byte[] body, Collection<String> secrets) {
        String text = redactText(decode(body), secrets);
        return truncateUtf8(text, MAX_SNIPPET_BYTES);
    }

    private static String redactText(String input, Collection<String> secrets) {
        Objects.requireNonNull(input, "input");
        String text = input.length() > MAX_INPUT_CHARS ? input.substring(0, cutPoint(input, MAX_INPUT_CHARS)) : input;
        // Longest secrets first, so a secret that contains another is removed whole.
        List<String> ordered = secrets.stream()
                .filter(secret -> secret != null && !secret.isEmpty())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        for (String secret : ordered) {
            text = text.replace(secret, REDACTED);
        }
        text = CREDENTIAL.matcher(text).replaceAll("$1$2" + REDACTED);
        StringBuilder clean = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            clean.append(Character.isISOControl(c) ? ' ' : c);
        }
        return clean.toString();
    }

    private static String decode(byte[] body) {
        int length = Math.min(body.length, MAX_INPUT_CHARS);
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        CharBuffer out = CharBuffer.allocate(length);
        decoder.decode(ByteBuffer.wrap(body, 0, length), out, true);
        decoder.flush(out);
        return out.flip().toString();
    }

    private static String truncateUtf8(String text, int maxBytes) {
        int bytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maxBytes) {
                return text.substring(0, i);
            }
            bytes += size;
            i += Character.charCount(codePoint);
        }
        return text;
    }

    /** A cut point at or below {@code max} that does not split a surrogate pair. */
    private static int cutPoint(String text, int max) {
        return Character.isLowSurrogate(text.charAt(max)) ? max - 1 : max;
    }
}
