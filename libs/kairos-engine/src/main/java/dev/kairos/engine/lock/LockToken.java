package dev.kairos.engine.lock;

import dev.kairos.domain.run.Fence;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The owner token stored in a run lock (LLD section 4.3): the fence of the dispatch, the id of the node that took the
 * lock and random bytes, written as {@code fence:nodeId:nonce}.
 *
 * <p>The random part makes every acquisition unique, so a second delivery of the same message to the same node never
 * owns the first delivery's lock. The fence and node id let the lease sweeper and operators see who holds a lock. The
 * token is not a secret: it identifies an owner and is compared only by the Lua scripts.
 *
 * @param fence the fence of the dispatch that took the lock
 * @param nodeId the node that took the lock: 1 to 64 characters of {@code A-Z a-z 0-9 . _ -}
 * @param nonce 32 lower-case hex characters (16 random bytes)
 */
public record LockToken(Fence fence, String nodeId, String nonce) {

    /** Allowed node id characters; the colon is excluded because it separates the token's parts. */
    public static final String NODE_ID_PATTERN = "^[A-Za-z0-9._-]+$";

    /** Longest node id. */
    public static final int MAX_NODE_ID_LENGTH = 64;

    private static final int NONCE_BYTES = 16;
    private static final Pattern NODE_ID = Pattern.compile(NODE_ID_PATTERN);
    private static final Pattern NONCE = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern TOKEN = Pattern.compile("^([0-9]{1,10}):([A-Za-z0-9._-]{1,64}):([0-9a-f]{32})$");

    /** Longest token text: a ten-digit fence, a 64-character node id, a 32-character nonce and two colons. */
    private static final int MAX_TOKEN_LENGTH = 10 + 1 + MAX_NODE_ID_LENGTH + 1 + 2 * NONCE_BYTES;

    /**
     * Validates the parts.
     *
     * @throws IllegalArgumentException if the node id or nonce is malformed
     */
    public LockToken {
        Objects.requireNonNull(fence, "fence");
        requireNodeId(nodeId);
        Objects.requireNonNull(nonce, "nonce");
        if (!NONCE.matcher(nonce).matches()) {
            throw new IllegalArgumentException("A lock token nonce is 32 lower-case hex characters");
        }
    }

    /**
     * Creates a new token with fresh random bytes.
     *
     * @param fence the fence of the dispatch taking the lock
     * @param nodeId this node's id
     * @param random the source of the random bytes
     * @return the token
     * @throws IllegalArgumentException if the node id is malformed
     */
    public static LockToken generate(Fence fence, String nodeId, SecureRandom random) {
        byte[] bytes = new byte[NONCE_BYTES];
        random.nextBytes(bytes);
        return new LockToken(fence, nodeId, HexFormat.of().formatHex(bytes));
    }

    /**
     * Parses the text stored in Redis.
     *
     * @param text the stored value
     * @return the token, or empty if the text is not a well-formed token
     */
    public static Optional<LockToken> parse(String text) {
        if (text.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        Matcher matcher = TOKEN.matcher(text);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        long fence = Long.parseLong(matcher.group(1));
        if (fence > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        return Optional.of(new LockToken(new Fence((int) fence), matcher.group(2), matcher.group(3)));
    }

    /**
     * Checks a node id.
     *
     * @param nodeId the node id
     * @return the node id
     * @throws IllegalArgumentException if it is empty, longer than 64 characters or has a character outside
     *     {@code A-Z a-z 0-9 . _ -}
     */
    public static String requireNodeId(String nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        if (nodeId.isEmpty()
                || nodeId.length() > MAX_NODE_ID_LENGTH
                || !NODE_ID.matcher(nodeId).matches()) {
            throw new IllegalArgumentException("A node id is 1 to 64 characters of A-Z, a-z, 0-9, '.', '_' or '-'");
        }
        return nodeId;
    }

    /**
     * Returns the text stored in Redis.
     *
     * @return {@code fence:nodeId:nonce}
     */
    public String value() {
        return fence.value() + ":" + nodeId + ":" + nonce;
    }
}
