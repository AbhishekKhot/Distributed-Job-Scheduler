package dev.kairos.messaging.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.model.OutboxTopic;
import dev.kairos.domain.model.Priority;
import dev.kairos.domain.model.PriorityTier;
import dev.kairos.messaging.serde.MessageType;
import dev.kairos.proto.ProtoIds;
import dev.kairos.testsupport.RepositoryFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The topic table against LLD section 4.2, the outbox codes and the compose topic script. */
class KairosTopicTest {

    private static final Pattern CREATE = Pattern.compile("^create (\\S+)\\s+(\\d+)\\s+(.{1,40})$");

    @Test
    void everyOutboxTopicPublishesToTheTopicOfTheSameNameAndCode() {
        for (OutboxTopic outbox : OutboxTopic.values()) {
            KairosTopic topic = KairosTopic.forOutbox(outbox);
            assertThat(topic.topicName()).isEqualTo(outbox.topicName());
            assertThat(topic.outboxTopic()).contains(outbox);
        }
        assertThat(Arrays.stream(KairosTopic.values())
                        .filter(topic -> topic.outboxTopic().isEmpty()))
                .containsExactly(KairosTopic.RUN_RESULT);
    }

    @Test
    void dispatchTopicsFollowThePriorityTiers() {
        assertThat(KairosTopic.dispatch(PriorityTier.HIGH)).isEqualTo(KairosTopic.RUN_HIGH);
        assertThat(KairosTopic.dispatch(PriorityTier.MEDIUM)).isEqualTo(KairosTopic.RUN_MEDIUM);
        assertThat(KairosTopic.dispatch(PriorityTier.LOW)).isEqualTo(KairosTopic.RUN_LOW);
        assertThat(KairosTopic.dispatch(Priority.of(10))).isEqualTo(KairosTopic.RUN_HIGH);
        assertThat(KairosTopic.dispatch(Priority.of(5))).isEqualTo(KairosTopic.RUN_MEDIUM);
        assertThat(KairosTopic.dispatch(Priority.of(1))).isEqualTo(KairosTopic.RUN_LOW);
        for (KairosTopic topic : KairosTopic.values()) {
            assertThat(topic.tier().isPresent()).isEqualTo(topic.messageType() == MessageType.DISPATCH);
            topic.tier()
                    .ifPresent(tier -> assertThat(KairosTopic.dispatch(tier)).isEqualTo(topic));
        }
    }

    @Test
    void keysAndMessagesMatchTheContracts() {
        assertThat(KairosTopic.RUN_HIGH.keyType()).isEqualTo(KeyType.TARGET_ID);
        assertThat(KairosTopic.RUN_RESULT.keyType()).isEqualTo(KeyType.TARGET_ID);
        assertThat(KairosTopic.RUN_CANCEL.keyType()).isEqualTo(KeyType.RUN_ID);
        assertThat(KairosTopic.RUN_COMPLETED.keyType()).isEqualTo(KeyType.DEFINITION_ID);
        assertThat(KairosTopic.RUN_DEAD.keyType()).isEqualTo(KeyType.DEFINITION_ID);
        assertThat(KairosTopic.RUN_RESULT.messageType()).isEqualTo(MessageType.RESULT);
        assertThat(KairosTopic.RUN_CANCEL.messageType()).isEqualTo(MessageType.CANCEL);
        assertThat(KairosTopic.RUN_COMPLETED.messageType()).isEqualTo(MessageType.COMPLETED);
        assertThat(KairosTopic.RUN_DEAD.messageType()).isEqualTo(MessageType.DEAD);

        UUID id = UUID.randomUUID();
        assertThat(KeyType.key(id))
                .hasSize(KeyType.KEY_LENGTH)
                .isEqualTo(ProtoIds.toBytes(id).toByteArray());
    }

    @Test
    void partitionsAndRetentionMatchTheComposeTopicScript() throws IOException {
        Map<String, String[]> script = new HashMap<>();
        for (String line : Files.readAllLines(RepositoryFiles.resolve("deploy/compose/kafka/create-topics.sh"))) {
            Matcher matcher = CREATE.matcher(line.strip());
            if (matcher.matches()) {
                script.put(matcher.group(1), new String[] {matcher.group(2), matcher.group(3)});
            }
        }
        assertThat(script).hasSize(KairosTopic.values().length);
        Map<String, Duration> units = Map.of("hour", Duration.ofHours(1), "day", Duration.ofDays(1));
        for (KairosTopic topic : KairosTopic.values()) {
            String[] entry = script.get(topic.topicName());
            assertThat(entry).as(topic.topicName()).isNotNull();
            assertThat(Integer.parseInt(entry[0])).as(topic.topicName()).isEqualTo(topic.partitions());
            assertThat(retention(entry[1], units)).as(topic.topicName()).isEqualTo(topic.retention());
        }
    }

    @Test
    void namesResolveBackToTopics() {
        for (KairosTopic topic : KairosTopic.values()) {
            assertThat(KairosTopic.fromName(topic.topicName())).contains(topic);
        }
        assertThat(KairosTopic.fromName("kairos.run.unknown")).isEmpty();
        assertThatThrownBy(() -> KeyType.key(null)).isInstanceOf(NullPointerException.class);
    }

    /** Reads a retention written as {@code "$day"}, {@code "$hour"} or {@code $((3 * day))} in the script. */
    private static Duration retention(String value, Map<String, Duration> units) {
        String expression = value.replaceAll("[\"$()]", "").strip();
        int star = expression.indexOf('*');
        if (star >= 0) {
            long factor = Long.parseLong(expression.substring(0, star).strip());
            return units.get(expression.substring(star + 1).strip()).multipliedBy(factor);
        }
        return units.get(expression);
    }
}
