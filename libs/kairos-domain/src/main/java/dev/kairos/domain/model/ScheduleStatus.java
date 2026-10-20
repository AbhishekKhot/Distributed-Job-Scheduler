package dev.kairos.domain.model;

/** Status of a stored schedule ({@code schedules.status}). Only active schedules fire. */
public enum ScheduleStatus implements CodedEnum {
    /** Fires when due. */
    ACTIVE(1),
    /** The job or workflow is paused. */
    PAUSED(2),
    /** Held because the job's endpoint is suspended; released when the endpoint resumes. */
    HELD(3);

    private static final CodeIndex<ScheduleStatus> INDEX = CodeIndex.of(values());

    private final int code;

    ScheduleStatus(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }

    /**
     * Returns the status for a database code.
     *
     * @param code the code
     * @return the status
     * @throws IllegalArgumentException if the code is unknown
     */
    public static ScheduleStatus fromCode(int code) {
        return INDEX.fromCode(code);
    }
}
