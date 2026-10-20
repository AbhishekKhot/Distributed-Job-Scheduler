package dev.kairos.engine.lock;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The replication identity of the Redis server a service is connected to, from {@code INFO replication}.
 *
 * <p>A new primary (after a failover or a restart without persistence) has a new replication id. The failover monitor
 * (T-225) watches for that change, because locks and sent markers written to the old primary may be lost.
 *
 * @param role the server's role
 * @param replicationId the {@code master_replid}: 40 lower-case hex characters
 * @param replicationOffset the {@code master_repl_offset}, not negative
 */
public record RedisTopology(Role role, String replicationId, long replicationOffset) {

    /** Longest {@code INFO replication} text that is parsed; the section is a few hundred bytes. */
    static final int MAX_INFO_LENGTH = 64 * 1024;

    private static final Pattern REPLICATION_ID = Pattern.compile("^[0-9a-f]{40}$");

    /**
     * Validates the parts.
     *
     * @throws IllegalArgumentException if the replication id is not 40 lower-case hex characters or the offset is
     *     negative
     */
    public RedisTopology {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(replicationId, "replicationId");
        if (!REPLICATION_ID.matcher(replicationId).matches()) {
            throw new IllegalArgumentException("A replication id is 40 lower-case hex characters");
        }
        if (replicationOffset < 0) {
            throw new IllegalArgumentException("A replication offset cannot be negative");
        }
    }

    /**
     * Parses the text of {@code INFO replication}.
     *
     * @param info the command's reply
     * @return the topology, or empty if the reply is too long or lacks the role or a valid replication id or offset
     */
    public static Optional<RedisTopology> parse(String info) {
        if (info.length() > MAX_INFO_LENGTH) {
            return Optional.empty();
        }
        String role = null;
        String replicationId = null;
        String offset = null;
        for (String line : info.split("\r?\n", -1)) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon);
            String value = line.substring(colon + 1).strip();
            switch (name) {
                case "role" -> role = value;
                case "master_replid" -> replicationId = value;
                case "master_repl_offset" -> offset = value;
                default -> {
                    // Other fields (connected replicas, backlog sizes) are not needed.
                }
            }
        }
        if (role == null || replicationId == null || offset == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RedisTopology(Role.of(role), replicationId, Long.parseLong(offset)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The role of a Redis server. */
    public enum Role {
        /** The primary ({@code role:master}). */
        PRIMARY,
        /** A replica ({@code role:slave}). */
        REPLICA,
        /** A role this code does not know. */
        UNKNOWN;

        static Role of(String text) {
            return switch (text.toLowerCase(Locale.ROOT)) {
                case "master" -> PRIMARY;
                case "slave", "replica" -> REPLICA;
                default -> UNKNOWN;
            };
        }
    }
}
