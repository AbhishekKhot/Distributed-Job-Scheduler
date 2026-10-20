/**
 * Protobuf serializers and deserializers per message type. Deserializing never throws: a bad value becomes a poison
 * marker that the consumer counts and skips.
 */
package dev.kairos.messaging.serde;
