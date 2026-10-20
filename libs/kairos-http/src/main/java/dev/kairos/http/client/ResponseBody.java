package dev.kairos.http.client;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A response body read within the size cap, with its JSON tree when the content type is JSON and the body parsed.
 *
 * <p>Immutable: the bytes and the tree are copied on the way out. {@link #toString()} shows only the size, because a
 * body may carry sensitive outputs.
 */
public final class ResponseBody {

    private static final ResponseBody EMPTY = new ResponseBody(new byte[0], null);

    private final byte[] bytes;
    private final @Nullable JsonNode json;

    private ResponseBody(byte[] bytes, @Nullable JsonNode json) {
        this.bytes = bytes;
        this.json = json;
    }

    /**
     * Returns the empty body.
     *
     * @return the empty body
     */
    public static ResponseBody empty() {
        return EMPTY;
    }

    /**
     * Wraps a body; the bytes and the tree are copied.
     *
     * @param bytes the bytes
     * @param json the decoded tree, or null if the body is not JSON
     * @return the body
     */
    public static ResponseBody of(byte[] bytes, @Nullable JsonNode json) {
        return new ResponseBody(bytes.clone(), json == null ? null : json.deepCopy());
    }

    /**
     * Returns a copy of the bytes.
     *
     * @return the bytes
     */
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * Returns the number of bytes.
     *
     * @return the size
     */
    public int size() {
        return bytes.length;
    }

    /**
     * Decodes the body as UTF-8, replacing malformed sequences.
     *
     * @return the text
     */
    public String text() {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Returns a copy of the JSON tree, for output extraction.
     *
     * @return the tree, or empty if the content type is not JSON or the body did not parse
     */
    public Optional<JsonNode> json() {
        return json == null ? Optional.empty() : Optional.of(json.deepCopy());
    }

    /** Shows the size only. */
    @Override
    public String toString() {
        return "ResponseBody[size=" + bytes.length + ", json=" + (json != null) + "]";
    }
}
