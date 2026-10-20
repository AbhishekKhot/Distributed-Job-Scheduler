package dev.kairos.domain.model;

/**
 * Kind of a stored schedule ({@code schedules.kind}).
 *
 * <p>{@code IMMEDIATE} jobs have no schedule row: their single run is created with the job.
 */
public enum ScheduleKind implements CodedEnum {
    /** Fires once at a fixed instant. */
    ONE_TIME(1),
    /** Fires on a 5-field cron expression in an IANA time zone. */
    CRON(2),
    /** Fires at a fixed interval of at least one second. */
    INTERVAL(3);

    private static final CodeIndex<ScheduleKind> INDEX = CodeIndex.of(values());

    private final int code;

    ScheduleKind(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }

    /**
     * Returns the kind for a database code.
     *
     * @param code the code
     * @return the kind
     * @throws IllegalArgumentException if the code is unknown
     */
    public static ScheduleKind fromCode(int code) {
        return INDEX.fromCode(code);
    }
}
