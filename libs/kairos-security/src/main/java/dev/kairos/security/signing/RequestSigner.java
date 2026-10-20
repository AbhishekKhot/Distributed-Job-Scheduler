package dev.kairos.security.signing;

import dev.kairos.domain.id.RunId;
import dev.kairos.security.keyset.TeamSigningKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Signs every request the executor sends to a target, so the target can check that it comes from Kairos on behalf of
 * the team and was not altered or replayed later.
 *
 * <p>Two headers are added:
 *
 * <ul>
 *   <li>{@value #TIMESTAMP_HEADER}: the signing time in Unix seconds;
 *   <li>{@value #SIGNATURE_HEADER}: {@code v<version>=<hex>}, the lower-case hex HMAC-SHA256, under the team's signing
 *       key of that version, of the canonical string
 *       <pre>{@code
 * METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + RUN_ID + "\n" + hex(SHA-256(BODY))
 * }</pre>
 *       where {@code METHOD} is upper case, {@code PATH} is the request target exactly as sent (path and query, for
 *       example {@code /v1/orders?limit=10}), {@code RUN_ID} is the run id (also sent as the {@code Idempotency-Key}
 *       header) and {@code BODY} is the raw body bytes (empty for no body).
 * </ul>
 *
 * <p>Verification on the target, with the key shown once as {@code ksig_v<n>_<base64url>} (Java, any HTTP stack):
 *
 * <pre>{@code
 * // keys: version -> raw key bytes = Base64.getUrlDecoder().decode(text after "ksig_v<n>_")
 * String header = request.getHeader("X-Kairos-Signature");          // "v2=3f1c..."
 * int version = Integer.parseInt(header.substring(1, header.indexOf('=')));
 * byte[] key = keys.get(version);                                    // reject if unknown
 * long timestamp = Long.parseLong(request.getHeader("X-Kairos-Timestamp"));
 * if (Math.abs(Instant.now().getEpochSecond() - timestamp) > 300) reject();
 * String canonical = request.getMethod() + "\n" + pathAndQuery + "\n" + timestamp + "\n"
 *         + request.getHeader("Idempotency-Key") + "\n"
 *         + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
 * Mac mac = Mac.getInstance("HmacSHA256");
 * mac.init(new SecretKeySpec(key, "HmacSHA256"));
 * byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
 * byte[] presented = HexFormat.of().parseHex(header.substring(header.indexOf('=') + 1));
 * if (!MessageDigest.isEqual(expected, presented)) reject();   // constant-time comparison
 * }</pre>
 *
 * <p>After a rotation the new key signs at once; targets keep the previous key until {@code previousKeyValidUntil}
 * and pick the key by the version in the header. {@link RequestSignatures} implements this check for Kairos's own
 * test target.
 *
 * <p>Thread-safe.
 */
public final class RequestSigner {

    /** The header carrying the signing time in Unix seconds. */
    public static final String TIMESTAMP_HEADER = "X-Kairos-Timestamp";

    /** The header carrying {@code v<version>=<hex HMAC>}. */
    public static final String SIGNATURE_HEADER = "X-Kairos-Signature";

    private final Clock clock;

    /**
     * Creates the signer.
     *
     * @param clock the source of the signing time
     */
    public RequestSigner(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Signs a request.
     *
     * @param key the team's signing key
     * @param method the HTTP method, for example {@code POST}
     * @param pathAndQuery the request target as sent, starting with {@code /}
     * @param run the run the request belongs to
     * @param body the request body, empty for none
     * @return the two headers to add
     * @throws IllegalArgumentException if the method or path contains characters that could forge the canonical form
     */
    public SignatureHeaders sign(TeamSigningKey key, String method, String pathAndQuery, RunId run, byte[] body) {
        long timestamp = clock.instant().getEpochSecond();
        byte[] canonical = canonical(method, pathAndQuery, timestamp, run.toString(), body);
        try {
            byte[] signature = key.mac().computeMac(canonical);
            return new SignatureHeaders(
                    Long.toString(timestamp),
                    "v" + key.version() + "=" + HexFormat.of().formatHex(signature));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("request signing failed", e);
        }
    }

    /**
     * Builds the canonical string to sign.
     *
     * @param method the HTTP method
     * @param pathAndQuery the request target
     * @param timestamp the signing time in Unix seconds
     * @param runId the run id text
     * @param body the body, empty for none
     * @return the UTF-8 bytes of the canonical string
     */
    static byte[] canonical(String method, String pathAndQuery, long timestamp, String runId, byte[] body) {
        requireToken(method);
        requirePath(pathAndQuery);
        String text = method.toUpperCase(Locale.ROOT) + "\n" + pathAndQuery + "\n" + timestamp + "\n" + runId + "\n"
                + HexFormat.of().formatHex(sha256(Objects.requireNonNull(body, "body")));
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void requireToken(String method) {
        Objects.requireNonNull(method, "method");
        if (method.isEmpty() || method.length() > 16) {
            throw new IllegalArgumentException("invalid HTTP method");
        }
        for (int i = 0; i < method.length(); i++) {
            char c = method.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                throw new IllegalArgumentException("invalid HTTP method");
            }
        }
    }

    private static void requirePath(String pathAndQuery) {
        Objects.requireNonNull(pathAndQuery, "pathAndQuery");
        if (!pathAndQuery.startsWith("/")) {
            throw new IllegalArgumentException("the request target must start with /");
        }
        for (int i = 0; i < pathAndQuery.length(); i++) {
            char c = pathAndQuery.charAt(i);
            // A rendered target is percent-encoded ASCII; control characters (CR, LF) would split the canonical form.
            if (c < 0x21 || c > 0x7e) {
                throw new IllegalArgumentException("the request target must be encoded ASCII");
            }
        }
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK", e);
        }
    }
}
