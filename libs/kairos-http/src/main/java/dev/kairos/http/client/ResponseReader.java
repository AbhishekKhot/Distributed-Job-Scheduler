package dev.kairos.http.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a response body with a hard size cap and decodes JSON for output extraction.
 *
 * <p>At most {@link #MAX_BODY_BYTES} (256 KB) are read; one byte more and reading stops with
 * {@link Result.TooLarge}, so the caller aborts the connection instead of draining it ({@code RESPONSE_TOO_LARGE}).
 * JSON is decoded only when the content type is {@code application/json} or {@code application/*+json}, with limits on
 * nesting, string and number length, and duplicate keys refused; a body that does not parse simply has no tree.
 *
 * <p>Thread-safe.
 */
public final class ResponseReader {

    /** The response size cap (LLD section 4: {@code RESPONSE_TOO_LARGE}). */
    public static final int MAX_BODY_BYTES = 256 * 1024;

    private static final int CHUNK = 8192;
    private static final int MAX_NESTING = 64;
    private static final int MAX_NUMBER_LENGTH = 100;

    private final int maxBytes;
    private final JsonMapper mapper;

    /** Creates a reader with the 256 KB cap. */
    public ResponseReader() {
        this(MAX_BODY_BYTES);
    }

    /**
     * Creates a reader with a smaller cap, for example for token responses.
     *
     * @param maxBytes the cap, 1 to 256 KB
     * @throws IllegalArgumentException if the cap is out of range
     */
    public ResponseReader(int maxBytes) {
        if (maxBytes < 1 || maxBytes > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("the response cap is 1 to " + MAX_BODY_BYTES + " bytes");
        }
        this.maxBytes = maxBytes;
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING)
                .maxNumberLength(MAX_NUMBER_LENGTH)
                .maxStringLength(maxBytes)
                .build();
        this.mapper = JsonMapper.builder(JsonFactory.builder()
                        .streamReadConstraints(constraints)
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .build())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    /**
     * Returns the cap.
     *
     * @return the largest body read, in bytes
     */
    public int maxBytes() {
        return maxBytes;
    }

    /**
     * Reads a body up to the cap. The stream is not closed.
     *
     * @param in the body stream
     * @param contentType the response {@code Content-Type}, or null
     * @return the body, or {@link Result.TooLarge} once the cap is exceeded
     * @throws IOException if reading fails
     */
    public Result read(InputStream in, @Nullable String contentType) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, CHUNK));
        byte[] chunk = new byte[CHUNK];
        int total = 0;
        while (true) {
            int n = in.read(chunk, 0, Math.min(chunk.length, maxBytes + 1 - total));
            if (n < 0) {
                break;
            }
            total += n;
            if (total > maxBytes) {
                return new Result.TooLarge();
            }
            out.write(chunk, 0, n);
        }
        byte[] bytes = out.toByteArray();
        return new Result.Complete(ResponseBody.of(bytes, decodeJson(bytes, contentType)));
    }

    /**
     * Decodes a body as JSON when the content type says it is JSON.
     *
     * @param bytes the body
     * @param contentType the {@code Content-Type}, or null
     * @return the tree, or null if the type is not JSON, the body is empty or it does not parse
     */
    public @Nullable JsonNode decodeJson(byte[] bytes, @Nullable String contentType) {
        if (bytes.length == 0 || bytes.length > maxBytes || !isJson(contentType)) {
            return null;
        }
        try {
            JsonNode tree = mapper.readTree(bytes);
            return tree == null || tree.isMissingNode() ? null : tree;
        } catch (JacksonException e) {
            return null;
        }
    }

    /**
     * Tells whether a content type is JSON: {@code application/json} or {@code application/<anything>+json},
     * parameters ignored.
     *
     * @param contentType the {@code Content-Type}, or null
     * @return true if it is JSON
     */
    public static boolean isJson(@Nullable String contentType) {
        if (contentType == null) {
            return false;
        }
        int semicolon = contentType.indexOf(';');
        String mediaType = (semicolon < 0 ? contentType : contentType.substring(0, semicolon))
                .trim()
                .toLowerCase(Locale.ROOT);
        return mediaType.equals("application/json")
                || (mediaType.startsWith("application/") && mediaType.endsWith("+json"));
    }

    /** The result of reading a body. */
    public sealed interface Result {

        /**
         * The whole body was read.
         *
         * @param body the body
         */
        record Complete(ResponseBody body) implements Result {}

        /** The body exceeded the cap; the rest was not read. */
        record TooLarge() implements Result {}
    }
}
