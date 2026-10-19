package dev.kairos.domain.model;

/** Which definition a redrive uses ({@code dead_letters.redrive_mode}). */
public enum RedriveMode implements CodedEnum {
    /** The job and endpoint versions pinned by the original run. */
    ORIGINAL(1),
    /** The current job and endpoint versions. */
    CURRENT(2);

    private static final CodeIndex<RedriveMode> INDEX = CodeIndex.of(values());

    private final int code;

    RedriveMode(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }

    /**
     * Returns the mode for a database code.
     *
     * @param code the code
     * @return the mode
     * @throws IllegalArgumentException if the code is unknown
     */
    public static RedriveMode fromCode(int code) {
        return INDEX.fromCode(code);
    }
}
