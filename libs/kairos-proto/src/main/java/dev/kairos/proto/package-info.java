/**
 * Kafka message contracts.
 *
 * <p>Java classes are generated from {@code src/main/proto/kairos/v1/*.proto} into
 * {@code dev.kairos.proto.v1}: {@code Dispatch} (run topics), {@code Result} (result topic),
 * {@code Cancel}, {@code Completed} and {@code Dead}. This package holds the hand-written helpers
 * that every producer and consumer shares: {@link dev.kairos.proto.ProtoIds} for the 16-byte id
 * encoding and {@link dev.kairos.proto.ProtoTimestamps} for timestamps.
 *
 * <p>Messages never carry secrets. Credentials travel as secret references only, and sensitive
 * outputs only in sealed (encrypted) form.
 */
@NullMarked
package dev.kairos.proto;

import org.jspecify.annotations.NullMarked;
