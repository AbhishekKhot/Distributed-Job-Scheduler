package dev.kairos.domain.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

class ShardKeyTest {

    @ParameterizedTest(name = "{1} -> {2}")
    @CsvFileSource(resources = "golden-vectors.csv", encoding = "UTF-8")
    void matchesGoldenVectors(String teamId, String name, int expectedShard) {
        assertThat(ShardKey.of(UUID.fromString(teamId), name)).isEqualTo(Shard.of(expectedShard));
    }

    @Test
    void separatorIsFixed() {
        assertThat(ShardKey.SEPARATOR).isEqualTo("/");
    }

    @Property
    void isDeterministicAndInRange(
            @ForAll long high, @ForAll long low, @ForAll @AlphaChars @StringLength(min = 1, max = 128) String name) {
        UUID teamId = new UUID(high, low);
        Shard first = ShardKey.of(teamId, name);
        assertThat(ShardKey.of(teamId, name)).isEqualTo(first);
        assertThat(first.value()).isBetween(0, Shard.MAX);
    }

    @Test
    void shardRejectsOutOfRangeValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> Shard.of(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> Shard.of(Shard.COUNT));
        assertThat(Shard.of(0).compareTo(Shard.of(Shard.MAX))).isNegative();
    }
}
