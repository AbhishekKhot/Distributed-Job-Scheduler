package dev.kairos.api.support.paging;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.TeamId;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encodes the sort-key values of the last row of a page into an opaque, authenticated cursor and back.
 *
 * <p>Format: unpadded base64url of {@code payload || HMAC-SHA256(key, context || payload)}, where the payload is a
 * version byte, a value count and each value as a 2-byte length plus UTF-8 bytes. The context binds the cursor to the
 * list it came from and to the caller's team, so a cursor cannot be edited, forged, moved to another list or replayed
 * by another team. Decoding checks the length and alphabet before anything else, compares the MAC in constant time
 * and parses the payload strictly; any failure is 400 {@code validation} on field {@code cursor}, without saying
 * why.
 *
 * <p>The key comes from the environment (32 random bytes as hex). Immutable and thread-safe.
 */
public final class CursorCodec {

    /** The longest cursor accepted. */
    public static final int MAX_LENGTH = 1024;

    /** The most values a cursor holds. */
    public static final int MAX_VALUES = 8;

    /** The longest value, in UTF-8 bytes. */
    public static final int MAX_VALUE_BYTES = 256;

    private static final String HMAC = "HmacSHA256";
    private static final int MAC_BYTES = 32;
    private static final int MIN_KEY_BYTES = 32;
    private static final byte VERSION = 1;
    private static final String CONTEXT_PREFIX = "kairos-cursor-v1|";

    private final SecretKeySpec key;

    /**
     * Creates the codec.
     *
     * @param key the HMAC key, at least 32 bytes; copied
     * @throws IllegalArgumentException if the key is too short
     */
    public CursorCodec(byte[] key) {
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("the cursor key must be at least 32 bytes");
        }
        this.key = new SecretKeySpec(key.clone(), HMAC);
    }

    /**
     * Creates the codec from a hex key.
     *
     * @param hexKey the key as hex, at least 64 characters
     * @return the codec
     * @throws IllegalArgumentException if the key is not hex or too short
     */
    public static CursorCodec fromHex(String hexKey) {
        return new CursorCodec(HexFormat.of().parseHex(hexKey));
    }

    /**
     * Encodes a cursor.
     *
     * @param list the list it belongs to, for example {@code jobs}
     * @param team the caller's team
     * @param values the sort-key values of the page's last row, 1 to {@value #MAX_VALUES}
     * @return the cursor
     * @throws IllegalArgumentException if there are too many values or one is too long
     */
    public String encode(String list, TeamId team, List<String> values) {
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(team, "team");
        if (values.isEmpty() || values.size() > MAX_VALUES) {
            throw new IllegalArgumentException("a cursor holds 1 to " + MAX_VALUES + " values");
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(VERSION);
        payload.write(values.size());
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("a cursor value is at most " + MAX_VALUE_BYTES + " bytes");
            }
            payload.write(bytes.length >>> 8);
            payload.write(bytes.length & 0xFF);
            payload.writeBytes(bytes);
        }
        byte[] body = payload.toByteArray();
        byte[] mac = mac(list, team, body);
        byte[] token = Arrays.copyOf(body, body.length + mac.length);
        System.arraycopy(mac, 0, token, body.length, mac.length);
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        if (cursor.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("the cursor is too long");
        }
        return cursor;
    }

    /**
     * Decodes a cursor.
     *
     * @param list the list the request is for
     * @param team the caller's team
     * @param cursor the {@code cursor} parameter
     * @return the sort-key values
     * @throws ValidationFailedException with field {@code cursor} and code {@code format} if the cursor is malformed,
     *     tampered with, or belongs to another list or team
     */
    public List<String> decode(String list, TeamId team, String cursor) {
        if (cursor.isEmpty() || cursor.length() > MAX_LENGTH || !isBase64Url(cursor)) {
            throw invalid();
        }
        byte[] token;
        try {
            token = Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (token.length < 2 + MAC_BYTES) {
            throw invalid();
        }
        byte[] body = Arrays.copyOf(token, token.length - MAC_BYTES);
        byte[] presented = Arrays.copyOfRange(token, body.length, token.length);
        if (!MessageDigest.isEqual(mac(list, team, body), presented)) {
            throw invalid();
        }
        return parse(body);
    }

    private static List<String> parse(byte[] body) {
        ByteBuffer buffer = ByteBuffer.wrap(body);
        if (buffer.get() != VERSION) {
            throw invalid();
        }
        int count = buffer.get() & 0xFF;
        if (count < 1 || count > MAX_VALUES) {
            throw invalid();
        }
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (buffer.remaining() < 2) {
                throw invalid();
            }
            int length = buffer.getShort() & 0xFFFF;
            if (length > MAX_VALUE_BYTES || buffer.remaining() < length) {
                throw invalid();
            }
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            try {
                values.add(StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString());
            } catch (CharacterCodingException e) {
                throw invalid();
            }
        }
        if (buffer.hasRemaining()) {
            throw invalid();
        }
        return List.copyOf(values);
    }

    private byte[] mac(String list, TeamId team, byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            mac.update((CONTEXT_PREFIX + list + "|" + team.value() + "|").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is required by every JDK", e);
        }
    }

    private static boolean isBase64Url(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean allowed =
                    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static ValidationFailedException invalid() {
        return ValidationFailedException.of("cursor", FieldError.FORMAT);
    }

    /** Leaves the key out. */
    @Override
    public String toString() {
        return "CursorCodec[key=<redacted>]";
    }
}
