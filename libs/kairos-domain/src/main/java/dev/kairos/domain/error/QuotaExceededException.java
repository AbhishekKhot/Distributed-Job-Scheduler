package dev.kairos.domain.error;

import java.util.Set;

/**
 * A team quota (jobs, executions per second, in-flight runs, workflow tasks, backfill or redrive
 * rate) would be exceeded ({@link ErrorCode#QUOTA_EXCEEDED}, status 429).
 */
public final class QuotaExceededException extends KairosException {

    /**
     * Creates an exception.
     *
     * @param detail a fixed, client-safe description naming the quota, never the team's values
     */
    public QuotaExceededException(String detail) {
        super(ErrorCode.QUOTA_EXCEEDED, detail, Set.of(429));
    }
}
