package dev.kairos.watcher.node;

import java.time.Instant;
import java.util.Objects;

/** Stable identity for one watcher process incarnation. */
public record NodeIdentity(String nodeId, String role, String version, Instant startedAt) {

    /** Validates values persisted in {@code scheduler.scheduler_nodes}. */
    public NodeIdentity {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(startedAt, "startedAt");
        if (nodeId.isBlank()
                || nodeId.length() > 128
                || role.isBlank()
                || role.length() > 128
                || version.isBlank()
                || version.length() > 64) {
            throw new IllegalArgumentException("Node registration fields are out of bounds");
        }
    }

    /**
     * Creates a watcher identity using the documented node-id format.
     * @param pod the pod or host name
     * @param startedAt process start time
     * @param version deployed application version
     * @return a unique process identity
     */
    public static NodeIdentity watcher(String pod, Instant startedAt, String version) {
        Objects.requireNonNull(pod, "pod");
        String safePod = pod.replaceAll("[^A-Za-z0-9-]", "-");
        if (safePod.isBlank()) safePod = "local";
        if (safePod.length() > 70) safePod = safePod.substring(0, 70);
        String id = "kairos-watcher-" + safePod + "-" + startedAt.toEpochMilli();
        return new NodeIdentity(id, "watcher", version, startedAt);
    }
}
