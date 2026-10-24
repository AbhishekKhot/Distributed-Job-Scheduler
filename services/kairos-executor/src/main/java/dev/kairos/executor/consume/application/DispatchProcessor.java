package dev.kairos.executor.consume.application;

import dev.kairos.proto.v1.Dispatch;

/** Handles one admitted dispatch. T-242 and T-243 provide the execution pipeline. */
@FunctionalInterface
public interface DispatchProcessor {

    /**
     * Processes a dispatch until it is safely rejected, deferred, or its result is published.
     *
     * @param dispatch the immutable Kafka dispatch
     * @throws Exception when processing did not reach a durable outcome; the consumer will retry it
     */
    void process(Dispatch dispatch) throws Exception;
}
