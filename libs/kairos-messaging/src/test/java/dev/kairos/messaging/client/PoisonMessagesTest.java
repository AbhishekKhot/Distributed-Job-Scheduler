package dev.kairos.messaging.client;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.messaging.MessagingFixtures;
import dev.kairos.messaging.serde.Decoded;
import dev.kairos.messaging.serde.PoisonReason;
import dev.kairos.messaging.topic.KairosTopic;
import dev.kairos.proto.v1.Cancel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/** Poison records are counted per topic and reason and skipped; valid records pass through. */
class PoisonMessagesTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PoisonMessages poison = new PoisonMessages(registry);

    @Test
    void validRecordsPassThrough() {
        Cancel cancel = MessagingFixtures.cancel();
        assertThat(poison.message(record("kairos.run.cancel", new Decoded.Valid<>(cancel))))
                .contains(cancel);
        assertThat(registry.find(PoisonMessages.METRIC).counter()).isNull();
    }

    @Test
    void poisonRecordsAreCountedAndSkipped() {
        assertThat(poison.message(record("kairos.run.cancel", new Decoded.Poison<Cancel>(PoisonReason.MALFORMED, 9))))
                .isEmpty();
        assertThat(poison.message(record("kairos.run.cancel", null))).isEmpty();

        assertThat(poison.count(KairosTopic.RUN_CANCEL, PoisonReason.MALFORMED)).isEqualTo(1);
        assertThat(poison.count(KairosTopic.RUN_CANCEL, PoisonReason.EMPTY)).isEqualTo(1);
    }

    @Test
    void foreignTopicsShareOneLabel() {
        poison.message(record("someone.elses.topic", new Decoded.Poison<Cancel>(PoisonReason.TOO_LARGE, 1)));

        assertThat(registry.get(PoisonMessages.METRIC)
                        .tag("topic", "other")
                        .tag("reason", "too_large")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    private static ConsumerRecord<byte[], Decoded<Cancel>> record(String topic, Decoded<Cancel> value) {
        return new ConsumerRecord<>(topic, 0, 42L, new byte[16], value);
    }
}
