/**
 * Compatibility check for the Protobuf contracts.
 *
 * <p>{@link dev.kairos.proto.compat.ProtoCompatibility} compares the released descriptor set in
 * {@code libs/kairos-proto/compat/} with the one built from the current sources and lists breaking
 * changes; {@link dev.kairos.proto.compat.ProtoCompatibilityCheck} runs it from the build, where it
 * is part of {@code check}.
 */
@NullMarked
package dev.kairos.proto.compat;

import org.jspecify.annotations.NullMarked;
