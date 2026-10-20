package dev.kairos.domain.shard;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Computes the shard of a job or workflow from its team and name.
 *
 * <p>The function is part of the storage format and is fixed forever:
 *
 * <pre>{@code
 * shard = floorMod(murmur3_32_fixed(utf8(teamId.toString() + "/" + name)), 256)
 * }</pre>
 *
 * <ul>
 *   <li>{@code teamId.toString()} is the canonical lower-case, hyphenated UUID form;
 *   <li>the separator is a single {@code '/'}, which neither a UUID nor a validated name can
 *       contain, so two different (team, name) pairs never produce the same input;
 *   <li>the bytes are UTF-8, hashed with Guava's {@code murmur3_32_fixed} (seed 0), and the signed
 *       32-bit result is reduced with {@link Math#floorMod(int, int)}.
 * </ul>
 *
 * <p>Golden test vectors pin the output. Changing any step would silently move existing jobs to
 * another shard, so the vectors must never be edited. Instances are stateless and thread-safe.
 */
public final class ShardKey {

    private static final HashFunction MURMUR3 = Hashing.murmur3_32_fixed();

    /** Separator between the team id and the name; part of the fixed storage format. */
    static final String SEPARATOR = "/";

    private ShardKey() {}

    /**
     * Returns the shard for a job or workflow.
     *
     * @param teamId the owning team's id, the value of a {@code TeamId}
     * @param name the job or workflow name, already validated; names are immutable
     * @return the shard, 0 to 255
     */
    public static Shard of(UUID teamId, String name) {
        String key = teamId.toString() + SEPARATOR + name;
        int hash = MURMUR3.hashString(key, StandardCharsets.UTF_8).asInt();
        return Shard.of(Math.floorMod(hash, Shard.COUNT));
    }
}
