package dev.kairos.messaging.topic;

import dev.kairos.proto.ProtoIds;
import java.util.Objects;
import java.util.UUID;

/**
 * What a topic's record key is (LLD section 4.2). Every key is a 16-byte big-endian id, the same bytes as the id fields
 * of the messages and the {@code outbox.msg_key} column, so all messages of one key land on one partition in order.
 */
public enum KeyType {
    /** The job id, or the workflow run id of a workflow task: keeps one target's dispatches and results in order. */
    TARGET_ID,
    /** The run id. */
    RUN_ID,
    /** The definition (job or workflow) id. */
    DEFINITION_ID;

    /** Length of every key. */
    public static final int KEY_LENGTH = ProtoIds.ID_LENGTH;

    /**
     * Encodes an id as a record key.
     *
     * @param id the id
     * @return 16 bytes, big-endian
     */
    public static byte[] key(UUID id) {
        return ProtoIds.toBytes(Objects.requireNonNull(id, "id")).toByteArray();
    }
}
