package dev.kairos.executor.call.application;

import dev.kairos.proto.v1.Result;

/** Durably publishes a result before the execution gates release the run lock. */
@FunctionalInterface
public interface ResultPublisher {
    /** Publishes one result and waits until Kafka acknowledges it. */
    void publish(Result result, java.util.UUID targetId, String traceParent) throws Exception;
}
