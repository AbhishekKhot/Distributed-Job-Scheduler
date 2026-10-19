package dev.kairos.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import java.util.function.Function;
import java.util.stream.Stream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.StringLength;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NamesTest {

    static Stream<Arguments> validNames() {
        return Stream.of(
                Arguments.of(fn(TeamSlug::of), "payments"),
                Arguments.of(fn(TeamSlug::of), "abc"),
                Arguments.of(fn(TeamSlug::of), "a" + "-".repeat(61) + "z"),
                Arguments.of(fn(EndpointName::of), "orders-create"),
                Arguments.of(fn(EndpointName::of), "ab"),
                Arguments.of(fn(JobName::of), "nightly-order-sync"),
                Arguments.of(fn(JobName::of), "Report_2026.v2"),
                Arguments.of(fn(JobName::of), "7"),
                Arguments.of(fn(JobName::of), "j".repeat(128)),
                Arguments.of(fn(WorkflowName::of), "order-fulfilment"),
                Arguments.of(fn(TaskKey::of), "createOrder"),
                Arguments.of(fn(TaskKey::of), "step_2-notify"),
                Arguments.of(fn(TaskKey::of), "t".repeat(64)));
    }

    static Stream<Arguments> invalidNames() {
        return Stream.of(
                Arguments.of(fn(TeamSlug::of), null, "name", FieldError.REQUIRED),
                Arguments.of(fn(TeamSlug::of), "", "name", FieldError.REQUIRED),
                Arguments.of(fn(TeamSlug::of), "ab", "name", FieldError.LENGTH),
                Arguments.of(fn(TeamSlug::of), "a".repeat(64), "name", FieldError.LENGTH),
                Arguments.of(fn(TeamSlug::of), "Payments", "name", FieldError.PATTERN),
                Arguments.of(fn(TeamSlug::of), "1payments", "name", FieldError.PATTERN),
                Arguments.of(fn(TeamSlug::of), "pay_ments", "name", FieldError.PATTERN),
                Arguments.of(fn(EndpointName::of), "a", "name", FieldError.LENGTH),
                Arguments.of(fn(EndpointName::of), "orders.create", "name", FieldError.PATTERN),
                Arguments.of(fn(EndpointName::of), "orders create", "name", FieldError.PATTERN),
                Arguments.of(fn(JobName::of), "j".repeat(129), "name", FieldError.LENGTH),
                Arguments.of(fn(JobName::of), "-leading-hyphen", "name", FieldError.PATTERN),
                Arguments.of(fn(JobName::of), "with space", "name", FieldError.PATTERN),
                Arguments.of(fn(JobName::of), "slash/name", "name", FieldError.PATTERN),
                Arguments.of(fn(JobName::of), "café", "name", FieldError.PATTERN),
                Arguments.of(fn(JobName::of), "line\nbreak", "name", FieldError.PATTERN),
                Arguments.of(fn(WorkflowName::of), "", "name", FieldError.REQUIRED),
                Arguments.of(fn(WorkflowName::of), "{{ run.id }}", "name", FieldError.PATTERN),
                Arguments.of(fn(TaskKey::of), null, "taskKey", FieldError.REQUIRED),
                Arguments.of(fn(TaskKey::of), "t".repeat(65), "taskKey", FieldError.LENGTH),
                Arguments.of(fn(TaskKey::of), "1st", "taskKey", FieldError.PATTERN),
                Arguments.of(fn(TaskKey::of), "tasks.login", "taskKey", FieldError.PATTERN));
    }

    @ParameterizedTest
    @MethodSource("validNames")
    void acceptsValidNames(Function<@Nullable String, Object> factory, String value) {
        assertThat(factory.apply(value)).hasToString(value);
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void rejectsInvalidNamesWithAStableCode(
            Function<@Nullable String, Object> factory, @Nullable String value, String field, String code) {
        assertThatExceptionOfType(ValidationFailedException.class)
                .isThrownBy(() -> factory.apply(value))
                .satisfies(e -> {
                    assertThat(e.fieldErrors()).containsExactly(new FieldError(field, code));
                    assertThat(e.getMessage()).isEqualTo(ValidationFailedException.DEFAULT_DETAIL);
                });
    }

    @Property
    void overlongInputIsRejectedOnLengthBeforeMatching(@ForAll @StringLength(min = 129, max = 10_000) String value) {
        assertThatExceptionOfType(ValidationFailedException.class)
                .isThrownBy(() -> JobName.of(value))
                .satisfies(e -> assertThat(e.fieldErrors()).containsExactly(new FieldError("name", FieldError.LENGTH)));
    }

    @Property
    void canonicalConstructorAppliesTheSameRule(@ForAll @StringLength(max = 70) String value) {
        boolean viaFactory = accepts(() -> TaskKey.of(value));
        boolean viaConstructor = accepts(() -> new TaskKey(value));

        assertThat(viaConstructor).isEqualTo(viaFactory);
    }

    private static boolean accepts(Runnable creation) {
        try {
            creation.run();
            return true;
        } catch (ValidationFailedException e) {
            return false;
        }
    }

    private static Function<@Nullable String, Object> fn(Function<@Nullable String, Object> factory) {
        return factory;
    }
}
