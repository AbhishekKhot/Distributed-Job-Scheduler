package dev.kairos.persistence.cell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CellLayoutTest {

    static final CellRange CELL_0 = new CellRange(0, 0, 127, "cell-0", CellStatus.ACTIVE);
    static final CellRange CELL_1 = new CellRange(1, 128, 255, "cell-1", CellStatus.ACTIVE);

    @Test
    void mapsEveryShardToItsRange() {
        CellLayout layout = CellLayout.of(List.of(CELL_1, CELL_0));

        assertThat(layout.rangeOf(0)).isEqualTo(CELL_0);
        assertThat(layout.rangeOf(127)).isEqualTo(CELL_0);
        assertThat(layout.rangeOf(128)).isEqualTo(CELL_1);
        assertThat(layout.rangeOf(255)).isEqualTo(CELL_1);
        assertThat(layout.ranges()).containsExactly(CELL_0, CELL_1);
    }

    @Test
    void supportsFourCellsOfSixtyFourShards() {
        CellLayout layout = CellLayout.of(List.of(
                new CellRange(0, 0, 63, "cell-0", CellStatus.ACTIVE),
                new CellRange(1, 64, 127, "cell-1", CellStatus.ACTIVE),
                new CellRange(2, 128, 191, "cell-2", CellStatus.DRAINING),
                new CellRange(3, 192, 255, "cell-3", CellStatus.READ_ONLY)));

        assertThat(layout.rangeOf(64).cellId()).isEqualTo(1);
        assertThat(layout.rangeOf(200).status()).isEqualTo(CellStatus.READ_ONLY);
    }

    @Test
    void rejectsGapsOverlapsAndRepeatedCells() {
        assertThatThrownBy(() -> CellLayout.of(List.of(CELL_0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shard 128 to no cell");
        assertThatThrownBy(() ->
                        CellLayout.of(List.of(CELL_0, CELL_1, new CellRange(2, 120, 130, "cell-2", CellStatus.ACTIVE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shard 120");
        assertThatThrownBy(
                        () -> CellLayout.of(List.of(CELL_0, new CellRange(0, 128, 255, "cell-1", CellStatus.ACTIVE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("twice");
        assertThatThrownBy(() -> CellLayout.of(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsShardsOutsideTheRange() {
        CellLayout layout = CellLayout.of(List.of(CELL_0, CELL_1));

        assertThatThrownBy(() -> layout.rangeOf(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> layout.rangeOf(256)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rangesValidateTheirFields() {
        assertThat(CELL_0.contains(127)).isTrue();
        assertThat(CELL_0.contains(128)).isFalse();
        assertThatThrownBy(() -> new CellRange(-1, 0, 1, "cell-0", CellStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CellRange(0, 10, 9, "cell-0", CellStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CellRange(0, 0, 256, "cell-0", CellStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CellRange(0, 0, 1, " ", CellStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statusParsesTheStoredText() {
        assertThat(CellStatus.fromText("ACTIVE")).isEqualTo(CellStatus.ACTIVE);
        assertThat(CellStatus.fromText("DRAINING")).isEqualTo(CellStatus.DRAINING);
        assertThat(CellStatus.fromText("READ_ONLY")).isEqualTo(CellStatus.READ_ONLY);
        assertThatThrownBy(() -> CellStatus.fromText("active")).isInstanceOf(IllegalArgumentException.class);
    }
}
