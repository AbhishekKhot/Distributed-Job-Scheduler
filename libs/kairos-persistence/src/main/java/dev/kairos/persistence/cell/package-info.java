/**
 * Cell routing: {@link dev.kairos.persistence.cell.CellRouter} maps a shard, or an id that embeds one, to the
 * {@link dev.kairos.persistence.cell.Cell} that holds it, using the {@code cell_map} layout from the control database
 * ({@link dev.kairos.persistence.cell.CellLayout}), cached and refreshed in the background.
 */
package dev.kairos.persistence.cell;
