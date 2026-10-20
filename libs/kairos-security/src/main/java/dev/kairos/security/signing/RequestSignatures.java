package dev.kairos.security.signing;

import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies {@code X-Kairos-Signature} the way a target does, with the exported {@code ksig_v<n>_...} keys: the
 * reference implementation of the check documented on {@link RequestSigner}, used by the mock target and the tests.
 *
 * <p>The signature must use a known key version, the timestamp must be within the tolerance of the verifier's clock,
 * and the HMAC is compared in constant time. Any malformed input is a failed verification, never an exception.
 *
 * <p>Immutable and thread-safe.
 */
public final class RequestSignatures {

    private static final String KEY_PREFIX = "ksig_v";
    private static final int KEY_BYTES = 32;
    private static final int MAX_HEADER_LENGTH = 128;

    private final Map<Integer, byte[]> keys;
    private final Clock clock;
    private final Duration tolerance;

    private RequestSignatures(Map<Integer, byte[]> keys, Clock clock, Duration tolerance) {
        this.keys = keys;
        this.clock = clock;
        this.tolerance = tolerance;
    }

    /**
     * Creates a verifier.
     *
     * @param exportedKeys the team's current and previous keys, as shown once ({@code ksig_v<n>_<base64url>})
     * @param clock the verifier's clock
     * @param tolerance the largest accepted difference between the timestamp and the clock
     * @return the verifier
     * @throws IllegalArgumentException if a key is malformed or two keys share a version
     */
    public static RequestSignatures of(List<String> exportedKeys, Clock clock, Duration tolerance) {
        Map<Integer, byte[]> keys = new HashMap<>();
        for (String exported : exportedKeys) {
            int separator = exported.indexOf('_', KEY_PREFIX.length());
            if (!exported.startsWith(KEY_PREFIX) || separator < 0) {
                throw new IllegalArgumentException("not a Kairos signing key");
            }
            int version = parseVersion(exported.substring(KEY_PREFIX.length(), separator));
            byte[] key;
            try {
                key = Base64.getUrlDecoder().decode(exported.substring(separator + 1));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("not a Kairos signing key");
            }
            if (version < 1 || key.length != KEY_BYTES || keys.putIfAbsent(version, key) != null) {
                throw new IllegalArgumentException("not a Kairos signing key, or a duplicate version");
            }
        }
        return new RequestSignatures(Map.copyOf(keys), Objects.requireNonNull(clock, "clock"), tolerance);
    }

    /**
     * Verifies a request.
     *
     * @param method the HTTP method
     * @param pathAndQuery the request target as received
     * @param runId the run id (the {@code Idempotency-Key} header)
     * @param body the raw body
     * @param timestampHeader the {@value RequestSigner#TIMESTAMP_HEADER} value
     * @param signatureHeader the {@value RequestSigner#SIGNATURE_HEADER} value
     * @return whether the signature is valid and fresh
     */
    public boolean verify(
            String method,
            String pathAndQuery,
            String runId,
            byte[] body,
            String timestampHeader,
            String signatureHeader) {
        if (signatureHeader.length() > MAX_HEADER_LENGTH || timestampHeader.length() > 19) {
            return false;
        }
        int equals = signatureHeader.indexOf('=');
        if (!signatureHeader.startsWith("v") || equals < 2) {
            return false;
        }
        int version = parseVersion(signatureHeader.substring(1, equals));
        byte[] key = keys.get(version);
        if (key == null) {
            return false;
        }
        long timestamp;
        byte[] presented;
        byte[] canonical;
        try {
            timestamp = Long.parseLong(timestampHeader);
            presented = HexFormat.of().parseHex(signatureHeader.substring(equals + 1));
            canonical = RequestSigner.canonical(method, pathAndQuery, timestamp, runId, body);
        } catch (IllegalArgumentException e) {
            return false;
        }
        long age = Math.abs(clock.instant().getEpochSecond() - timestamp);
        if (age > tolerance.toSeconds()) {
            return false;
        }
        return MessageDigest.isEqual(hmac(key, canonical), presented);
    }

    private static int parseVersion(String digits) {
        if (digits.isEmpty() || digits.length() > 6) {
            return -1;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                return -1;
            }
        }
        return Integer.parseInt(digits);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is required by every JDK", e);
        }
    }

    /** Leaves the keys out. */
    @Override
    public String toString() {
        return "RequestSignatures[versions=" + keys.keySet() + "]";
    }
}
