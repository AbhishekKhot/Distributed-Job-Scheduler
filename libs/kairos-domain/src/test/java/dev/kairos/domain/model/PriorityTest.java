package dev.kairos.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PriorityTest {

    @ParameterizedTest
    @CsvSource({"1, LOW", "4, LOW", "5, MEDIUM", "7, MEDIUM", "8, HIGH", "10, HIGH"})
    void mapsToTiers(int value, PriorityTier tier) {
        assertThat(Priority.of(value).tier()).isEqualTo(tier);
    }

    @Property
    void higherPriorityNeverHasALowerTier(@ForAll @IntRange(min = 1, max = 9) int value) {
        Priority lower = Priority.of(value);
        Priority higher = Priority.of(value + 1);

        assertThat(higher).isGreaterThan(lower);
        assertThat(rank(higher.tier())).isGreaterThanOrEqualTo(rank(lower.tier()));
    }

    private static int rank(PriorityTier tier) {
        return switch (tier) {
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
        };
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 11, Integer.MAX_VALUE})
    void rejectsValuesOutsideOneToTen(int value) {
        assertThatExceptionOfType(ValidationFailedException.class)
                .isThrownBy(() -> Priority.of(value))
                .satisfies(
                        e -> assertThat(e.fieldErrors()).containsExactly(new FieldError("priority", FieldError.RANGE)));
    }

    @ParameterizedTest
    @CsvSource({"HIGH, RUN_HIGH", "MEDIUM, RUN_MEDIUM", "LOW, RUN_LOW"})
    void eachTierHasItsDispatchTopic(PriorityTier tier, OutboxTopic topic) {
        assertThat(OutboxTopic.dispatchTopic(tier)).isEqualTo(topic);
        assertThat(topic.topicName()).isEqualTo("kairos.run." + tier.name().toLowerCase(java.util.Locale.ROOT));
    }
}
