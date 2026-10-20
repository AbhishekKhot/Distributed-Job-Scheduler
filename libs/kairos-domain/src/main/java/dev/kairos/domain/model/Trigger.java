package dev.kairos.domain.model;

/** What created a run or workflow run ({@code trigger} columns). */
public enum Trigger implements CodedEnum {
    /** A schedule fired. */
    SCHEDULE(1),
    /** An {@code IMMEDIATE} job was created. */
    IMMEDIATE(2),
    /** A team member or API key asked for a run now. */
    MANUAL(3),
    /** A backfill request for past occurrences. */
    BACKFILL(4),
    /** A dead-letter entry was redriven. */
    REDRIVE(5);

    private static final CodeIndex<Trigger> INDEX = CodeIndex.of(values());

    private final int code;

    Trigger(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }

    /**
     * Returns the trigger for a database code.
     *
     * @param code the code
     * @return the trigger
     * @throws IllegalArgumentException if the code is unknown
     */
    public static Trigger fromCode(int code) {
        return INDEX.fromCode(code);
    }
}
