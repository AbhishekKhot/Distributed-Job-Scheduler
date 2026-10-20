package dev.kairos.domain.model;

/**
 * How a cron schedule handles daylight-saving transitions ({@code schedules.dst_policy}).
 *
 * <p>Each policy combines a rule for local times that do not exist (the clocks jump forward over
 * them, a gap) with a rule for local times that occur twice (the clocks fall back, an overlap).
 */
public enum DstPolicy implements CodedEnum {
    /** Run at the first valid instant after a gap; run once, at the first occurrence, in an overlap. The default. */
    GAP_SHIFT_FORWARD_OVERLAP_FIRST(1, Gap.SHIFT_FORWARD, Overlap.FIRST),
    /** Run at the first valid instant after a gap; run at both occurrences in an overlap. */
    GAP_SHIFT_FORWARD_OVERLAP_BOTH(2, Gap.SHIFT_FORWARD, Overlap.BOTH),
    /** Skip a time that falls in a gap; run once, at the first occurrence, in an overlap. */
    GAP_SKIP_OVERLAP_FIRST(3, Gap.SKIP, Overlap.FIRST),
    /** Skip a time that falls in a gap; run at both occurrences in an overlap. */
    GAP_SKIP_OVERLAP_BOTH(4, Gap.SKIP, Overlap.BOTH);

    /** Policy used when a schedule does not set one. */
    public static final DstPolicy DEFAULT = GAP_SHIFT_FORWARD_OVERLAP_FIRST;

    private static final CodeIndex<DstPolicy> INDEX = CodeIndex.of(values());

    private final int code;
    private final Gap gap;
    private final Overlap overlap;

    DstPolicy(int code, Gap gap, Overlap overlap) {
        this.code = code;
        this.gap = gap;
        this.overlap = overlap;
    }

    /** Handling of a local time inside a forward transition. */
    public enum Gap {
        /** Run at the first valid instant after the gap. */
        SHIFT_FORWARD,
        /** Do not run that occurrence. */
        SKIP
    }

    /** Handling of a local time that occurs twice in a backward transition. */
    public enum Overlap {
        /** Run once, at the earlier offset. */
        FIRST,
        /** Run at both offsets. */
        BOTH
    }

    @Override
    public int code() {
        return code;
    }

    /**
     * Returns the rule for times in a gap.
     *
     * @return the gap rule
     */
    public Gap gap() {
        return gap;
    }

    /**
     * Returns the rule for times in an overlap.
     *
     * @return the overlap rule
     */
    public Overlap overlap() {
        return overlap;
    }

    /**
     * Returns the policy for a database code.
     *
     * @param code the code
     * @return the policy
     * @throws IllegalArgumentException if the code is unknown
     */
    public static DstPolicy fromCode(int code) {
        return INDEX.fromCode(code);
    }
}
