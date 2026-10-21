package dev.kairos.api.support.ratelimit;

/**
 * The rate limits of the API. Each has its own buckets, keyed by the subject it limits (an IP address, an account,
 * a user or a team); the capacity and refill period are configured per limit under {@code kairos.api.rate-limits}.
 */
public enum RateLimit {
    /** Login attempts per client IP address (T-202). */
    LOGIN_PER_IP("login-ip"),
    /** Login attempts per account, keyed by the normalised email (T-202). */
    LOGIN_PER_ACCOUNT("login-account"),
    /** Signups per client IP address (T-202). */
    SIGNUP_PER_IP("signup-ip"),
    /** Password changes per user (T-202). */
    PASSWORD_CHANGE_PER_USER("password-change-user"),
    /** Team creations per user (T-203a). */
    TEAM_CREATE_PER_USER("team-create-user"),
    /** Member additions per user (T-203a). */
    MEMBER_ADD_PER_USER("member-add-user"),
    /** Endpoint test calls per team (T-204). */
    ENDPOINT_TEST_PER_TEAM("endpoint-test-team"),
    /** Every authenticated request of a team, applied by the API itself. */
    TEAM_API("team-api");

    private final String key;

    RateLimit(String key) {
        this.key = key;
    }

    /**
     * Returns the stable name used in Redis keys, for example {@code login-ip}.
     *
     * @return the key segment
     */
    public String key() {
        return key;
    }
}
