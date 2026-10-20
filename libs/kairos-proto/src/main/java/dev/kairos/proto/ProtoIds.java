package dev.kairos.proto;

import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Converts ids between {@link UUID} and the 16-byte form used by every Kairos message.
 *
 * <p>The encoding is big-endian: the most significant 64 bits first, then the least significant 64
 * bits. It is the same byte order as PostgreSQL's {@code uuid} type, so a Kafka key built from a
 * target id sorts and hashes the same way everywhere. This class is stateless and thread-safe.
 */
public final class ProtoIds {

    /** Length in bytes of an encoded id. */
    public static final int ID_LENGTH = 16;

    private ProtoIds() {}

    /**
     * Encodes an id as 16 big-endian bytes.
     *
     * @param id the id to encode
     * @return the encoded id, always {@value #ID_LENGTH} bytes long
     */
    public static ByteString toBytes(UUID id) {
        ByteBuffer buffer = ByteBuffer.allocate(ID_LENGTH);
        buffer.putLong(id.getMostSignificantBits());
        buffer.putLong(id.getLeastSignificantBits());
        buffer.flip();
        return ByteString.copyFrom(buffer);
    }

    /**
     * Decodes an id written by {@link #toBytes(UUID)}.
     *
     * @param bytes the encoded id
     * @return the id
     * @throws IllegalArgumentException if {@code bytes} is not exactly {@value #ID_LENGTH} bytes
     *     long, for example because the field was not set
     */
    public static UUID toUuid(ByteString bytes) {
        if (bytes.size() != ID_LENGTH) {
            // The length is safe to report; the content is never echoed.
            throw new IllegalArgumentException(
                    "An id must be " + ID_LENGTH + " bytes long, but was " + bytes.size() + " bytes");
        }
        ByteBuffer buffer = bytes.asReadOnlyByteBuffer();
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
