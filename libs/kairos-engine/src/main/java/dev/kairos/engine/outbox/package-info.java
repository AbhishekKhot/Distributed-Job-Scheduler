/**
 * The transactional outbox (HLD section 6): every message caused by a state change is written to the cell's
 * {@code outbox} table in the same transaction as the change, and the outbox relay (kairos-watcher) publishes it to
 * Kafka afterwards.
 *
 * <p>{@link dev.kairos.engine.outbox.OutboxMessage} is one row to write (topic, Kafka key, serialized Protobuf payload,
 * publish time), built from a {@code Dispatch}, {@code Cancel}, {@code Completed} or {@code Dead} message.
 * {@link dev.kairos.engine.outbox.OutboxWriter} inserts rows into the caller's open cell transaction and refuses to
 * write outside one, which {@link dev.kairos.engine.outbox.CellTransactionGuard} checks. Nothing in this package talks
 * to Kafka; payloads are bytes here.
 */
package dev.kairos.engine.outbox;
