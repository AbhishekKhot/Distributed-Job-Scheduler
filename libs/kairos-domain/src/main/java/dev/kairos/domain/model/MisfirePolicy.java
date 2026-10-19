package dev.kairos.domain.model;

/** What happens to fires missed while a schedule could not fire ({@code schedules.misfire_policy}). */
public enum MisfirePolicy implements CodedEnum {
    /** Run every missed occurrence, up to {@code max_catchup}. */
    RUN_ALL(1),
    /** Run only the most recent missed occurrence. The default. */
    RUN_LATEST(2),
    /** Run none of the missed occurrences. */
    SKIP(3);

    /** Policy used when a job or workflow does not set one. */
    public static final MisfirePolicy DEFAULT = RUN_LATEST;

    private static final CodeIndex<MisfirePolicy> INDEX = CodeIndex.of(values());

    private final int code;

    MisfirePolicy(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }

    /**
     * Returns the policy for a database code.
     *
     * @param code the code
     * @return the policy
     * @throws IllegalArgumentException if the code is unknown
     */
    public static MisfirePolicy fromCode(int code) {
        return INDEX.fromCode(code);
    }
}
