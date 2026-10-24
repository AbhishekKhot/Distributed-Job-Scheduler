package dev.kairos.executor.consume.application;

import com.google.protobuf.ByteString;
import dev.kairos.domain.id.KairosId;
import dev.kairos.proto.v1.Dispatch;
import java.nio.ByteBuffer;
import java.util.UUID;

/** Converts and validates the fixed-width UUIDs carried by dispatch messages. */
public final class DispatchIds {

    private DispatchIds() {}

    /** Converts a 16-byte UUID and checks Kairos UUIDv7 layout. */
    public static UUID uuid(ByteString bytes) {
        if (bytes.size() != 16) {
            throw new IllegalArgumentException("Dispatch identifiers must contain 16 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
        return KairosId.requireKairosId(new UUID(buffer.getLong(), buffer.getLong()));
    }

    /** Validates identifiers needed by quota admission before a worker touches the database. */
    public static void validateForAdmission(Dispatch dispatch) {
        UUID runId = uuid(dispatch.getRunId());
        uuid(dispatch.getTeamId());
        uuid(dispatch.getEndpointId());
        uuid(dispatch.getTargetId());
        if (KairosId.shardOf(runId).value() != dispatch.getShard()) {
            throw new IllegalArgumentException("Dispatch shard does not match its run id");
        }
    }
}
