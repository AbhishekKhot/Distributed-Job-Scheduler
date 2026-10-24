package dev.kairos.executor.call.infrastructure;

import dev.kairos.executor.call.application.ResultPublisher;
import dev.kairos.messaging.client.KafkaPublisher;
import dev.kairos.messaging.client.OutgoingRecord;
import dev.kairos.messaging.topic.KairosTopic;
import dev.kairos.proto.v1.Result;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/** Waits for the acks=all result publication before completing a dispatch action. */
public final class KafkaResultPublisher implements ResultPublisher {

    private final KafkaPublisher publisher;

    /** Creates the result publisher. */
    public KafkaResultPublisher(KafkaPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    @Override
    public void publish(Result result, UUID targetId, String traceParent) throws Exception {
        try {
            publisher
                    .publish(OutgoingRecord.of(KairosTopic.RUN_RESULT, targetId, result, traceParent))
                    .get();
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Kafka did not acknowledge the result", failure.getCause());
        }
    }
}
