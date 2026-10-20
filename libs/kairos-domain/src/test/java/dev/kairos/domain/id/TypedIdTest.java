package dev.kairos.domain.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.shard.Shard;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TypedIdTest {

    private static final String SAMPLE = "0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e";

    static Stream<Arguments> parsers() {
        return Stream.of(
                Arguments.of("teamId", (Function<String, TypedId>) TeamId::parse),
                Arguments.of("userId", (Function<String, TypedId>) UserId::parse),
                Arguments.of("endpointId", (Function<String, TypedId>) EndpointId::parse),
                Arguments.of("jobId", (Function<String, TypedId>) JobId::parse),
                Arguments.of("workflowId", (Function<String, TypedId>) WorkflowId::parse),
                Arguments.of("runId", (Function<String, TypedId>) RunId::parse),
                Arguments.of("workflowRunId", (Function<String, TypedId>) WorkflowRunId::parse),
                Arguments.of("dlqId", (Function<String, TypedId>) DlqId::parse));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parsers")
    void parsesAndPrintsCanonicalText(String field, Function<String, TypedId> parser) {
        TypedId id = parser.apply(SAMPLE);

        assertThat(id.value()).isEqualTo(UUID.fromString(SAMPLE));
        assertThat(id).hasToString(SAMPLE);
        if (id instanceof ShardedId sharded) {
            assertThat(sharded.shard()).isEqualTo(Shard.of(0x0e));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parsers")
    void reportsMalformedTextOnItsOwnField(String field, Function<String, TypedId> parser) {
        assertThatExceptionOfType(ValidationFailedException.class)
                .isThrownBy(() -> parser.apply("not-an-id"))
                .satisfies(e -> assertThat(e.fieldErrors()).containsExactly(new FieldError(field, FieldError.FORMAT)));
    }

    @Test
    void constructorsRejectNonKairosUuids() {
        UUID random = UUID.randomUUID();

        assertThatIllegalArgumentException().isThrownBy(() -> new TeamId(random));
        assertThatIllegalArgumentException().isThrownBy(() -> new UserId(random));
        assertThatIllegalArgumentException().isThrownBy(() -> new EndpointId(random));
        assertThatIllegalArgumentException().isThrownBy(() -> new JobId(random));
        assertThatIllegalArgumentException().isThrownBy(() -> new WorkflowId(random));
        assertThatIllegalArgumentException().isThrownBy(() -> new RunId(random));
        assertThatIllegalArgumentException().isThrownBy(() -> new WorkflowRunId(random));
        assertThatIllegalArgumentException().isThrownBy(() -> new DlqId(random));
    }

    @Test
    void idsOfDifferentResourcesAreNeverEqual() {
        UUID value = UUID.fromString(SAMPLE);

        assertThat(new JobId(value)).isNotEqualTo(new RunId(value));
        assertThat(new JobId(value)).isEqualTo(JobId.parse(SAMPLE));
    }
}
