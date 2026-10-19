package dev.kairos.domain.model;

/** Triage status of a dead-letter entry ({@code dead_letters.status}). */
public enum DlqStatus implements CodedEnum {
    /** Waiting for a team member. Never purged automatically. */
    OPEN(1),
    /** A team member has taken the entry. Never purged automatically. */
    ACKNOWLEDGED(2),
    /** Resolved by running it again. */
    REDRIVEN(3),
    /** Resolved without running it again. */
    DISCARDED(4);

    private static final CodeIndex<DlqStatus> INDEX = CodeIndex.of(values());

    private final int code;

    DlqStatus(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }

    /**
     * Tells whether the entry is resolved, which makes it eligible for the retention purge.
     *
     * @return true for {@link #REDRIVEN} and {@link #DISCARDED}
     */
    public boolean isResolved() {
        return this == REDRIVEN || this == DISCARDED;
    }

    /**
     * Returns the status for a database code.
     *
     * @param code the code
     * @return the status
     * @throws IllegalArgumentException if the code is unknown
     */
    public static DlqStatus fromCode(int code) {
        return INDEX.fromCode(code);
    }
}
