package dev.kairos.migrations.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.kairos.migrations.schema.CellMapSeeder.CellRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CellMapSeederTest {

    @Test
    void twoCellsSplitTheShardsInHalves() {
        assertThat(CellMapSeeder.layout(2))
                .containsExactly(new CellRange(0, 0, 127, "cell-0"), new CellRange(1, 128, 255, "cell-1"));
    }

    @Test
    void fourCellsGetSixtyFourShardsEach() {
        assertThat(CellMapSeeder.layout(4))
                .extracting(CellRange::shardFrom, CellRange::shardTo)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, 63),
                        org.assertj.core.groups.Tuple.tuple(64, 127),
                        org.assertj.core.groups.Tuple.tuple(128, 191),
                        org.assertj.core.groups.Tuple.tuple(192, 255));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3, 5, 512})
    void cellCountsThatDoNotDivideTheShardsAreRejected(int cellCount) {
        assertThatIllegalArgumentException().isThrownBy(() -> CellMapSeeder.layout(cellCount));
    }
}
