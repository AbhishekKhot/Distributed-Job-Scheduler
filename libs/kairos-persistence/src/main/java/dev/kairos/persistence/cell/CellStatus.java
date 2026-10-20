package dev.kairos.persistence.cell;

/**
 * The {@code status} of a cell in {@code cell_map} (LLD section 2.2).
 *
 * <p>Routing ignores the status: a shard always routes to the cell that holds it. Writers that must not write to a
 * cell being moved check {@link Cell#status()} themselves.
 */
public enum CellStatus {
    /** Serving reads and writes. */
    ACTIVE,
    /** Its shards are being moved to another cell. */
    DRAINING,
    /** Serving reads only. */
    READ_ONLY;

    /**
     * Parses the text stored in {@code cell_map.status}.
     *
     * @param text {@code ACTIVE}, {@code DRAINING} or {@code READ_ONLY}
     * @return the status
     * @throws IllegalArgumentException if the text is none of these
     */
    public static CellStatus fromText(String text) {
        return switch (text) {
            case "ACTIVE" -> ACTIVE;
            case "DRAINING" -> DRAINING;
            case "READ_ONLY" -> READ_ONLY;
            default -> throw new IllegalArgumentException("Unknown cell status");
        };
    }
}
