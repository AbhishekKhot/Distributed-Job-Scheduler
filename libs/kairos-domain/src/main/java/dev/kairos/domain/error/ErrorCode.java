package dev.kairos.domain.error;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Catalogue of the problem types the API returns (LLD section 3).
 *
 * <p>Each code carries the stable {@code type} slug, the problem {@code title} and the HTTP status
 * the LLD assigns to it. The slugs are part of the public contract: a code is never renamed or
 * reused, only added. The domain raises codes through {@link KairosException}; only the API layer
 * turns them into responses.
 */
public enum ErrorCode {
    /** {@code /errors/already-in-team}, status 409. */
    ALREADY_IN_TEAM("already-in-team", "Already in a team", 409),
    /** {@code /errors/ca-bundle-exists}, status 409. */
    CA_BUNDLE_EXISTS("ca-bundle-exists", "CA bundle exists", 409),
    /** {@code /errors/ca-bundle-in-use}, status 409. */
    CA_BUNDLE_IN_USE("ca-bundle-in-use", "CA bundle in use", 409),
    /** {@code /errors/cycle-detected}, status 400. */
    CYCLE_DETECTED("cycle-detected", "Invalid workflow", 400),
    /** {@code /errors/dlq-resolved}, status 409. */
    DLQ_RESOLVED("dlq-resolved", "Already resolved", 409),
    /** {@code /errors/email-taken}, status 409. */
    EMAIL_TAKEN("email-taken", "Email already registered", 409),
    /** {@code /errors/endpoint-in-use}, status 409. */
    ENDPOINT_IN_USE("endpoint-in-use", "Endpoint in use", 409),
    /** {@code /errors/endpoint-name-taken}, status 409. */
    ENDPOINT_NAME_TAKEN("endpoint-name-taken", "Endpoint name taken", 409),
    /** {@code /errors/endpoint-not-found}, status 400. */
    ENDPOINT_NOT_FOUND("endpoint-not-found", "Unknown endpoint", 400),
    /** {@code /errors/endpoint-suspended}, status 409. */
    ENDPOINT_SUSPENDED("endpoint-suspended", "Endpoint suspended", 409),
    /** {@code /errors/forbidden-for-api-key}, status 403. */
    FORBIDDEN_FOR_API_KEY("forbidden-for-api-key", "Not allowed for API keys", 403),
    /** {@code /errors/idempotency-key-reused}, status 409. */
    IDEMPOTENCY_KEY_REUSED("idempotency-key-reused", "Idempotency key reused", 409),
    /** {@code /errors/if-match-required}, status 428. */
    IF_MATCH_REQUIRED("if-match-required", "Precondition required", 428),
    /** {@code /errors/internal}, status 500. */
    INTERNAL("internal", "Internal error", 500),
    /** {@code /errors/invalid-credentials}, status 401. */
    INVALID_CREDENTIALS("invalid-credentials", "Login failed", 401),
    /** {@code /errors/invalid-current-password}, status 400. */
    INVALID_CURRENT_PASSWORD("invalid-current-password", "Password not changed", 400),
    /** {@code /errors/invalid-placeholder}, status 400. */
    INVALID_PLACEHOLDER("invalid-placeholder", "Invalid workflow", 400),
    /** {@code /errors/invalid-refresh-token}, status 401. */
    INVALID_REFRESH_TOKEN("invalid-refresh-token", "Refresh failed", 401),
    /** {@code /errors/invalid-state}, status 409. */
    INVALID_STATE("invalid-state", "Invalid state", 409),
    /** {@code /errors/job-name-taken}, status 409. */
    JOB_NAME_TAKEN("job-name-taken", "Job name taken", 409),
    /** {@code /errors/last-member}, status 409. */
    LAST_MEMBER("last-member", "Last member", 409),
    /** {@code /errors/name-immutable}, status 400. */
    NAME_IMMUTABLE("name-immutable", "Name cannot change", 400),
    /** {@code /errors/no-team}, status 403. */
    NO_TEAM("no-team", "No team", 403),
    /** {@code /errors/not-found}, status 404. */
    NOT_FOUND("not-found", "Not found", 404),
    /** {@code /errors/not-recurring}, status 409. */
    NOT_RECURRING("not-recurring", "Not a recurring job", 409),
    /** {@code /errors/quota-exceeded}, status 429. */
    QUOTA_EXCEEDED("quota-exceeded", "Quota exceeded", 429),
    /** {@code /errors/range-too-large}, status 400. */
    RANGE_TOO_LARGE("range-too-large", "Range too large", 400),
    /** {@code /errors/rate-limited}, status 429. */
    RATE_LIMITED("rate-limited", "Too many requests", 429),
    /** {@code /errors/render-failed}, status 400. */
    RENDER_FAILED("render-failed", "Request could not be built", 400),
    /** {@code /errors/run-final}, status 409. */
    RUN_FINAL("run-final", "Already finished", 409),
    /** {@code /errors/secret-exists}, status 409. */
    SECRET_EXISTS("secret-exists", "Secret exists", 409),
    /** {@code /errors/secret-in-use}, status 409. */
    SECRET_IN_USE("secret-in-use", "Secret in use", 409),
    /** {@code /errors/secret-not-found}, status 400. */
    SECRET_NOT_FOUND("secret-not-found", "Unknown secret", 400),
    /** {@code /errors/target-not-allowed}, status 400. */
    TARGET_NOT_ALLOWED("target-not-allowed", "Target not allowed", 400),
    /** {@code /errors/team-name-taken}, status 409. */
    TEAM_NAME_TAKEN("team-name-taken", "Team name taken", 409),
    /** {@code /errors/too-many-tasks}, status 400. */
    TOO_MANY_TASKS("too-many-tasks", "Invalid workflow", 400),
    /** {@code /errors/unauthenticated}, status 401. */
    UNAUTHENTICATED("unauthenticated", "Authentication required", 401),
    /** {@code /errors/unavailable}, status 503. */
    UNAVAILABLE("unavailable", "Service unavailable", 503),
    /** {@code /errors/user-in-another-team}, status 409. */
    USER_IN_ANOTHER_TEAM("user-in-another-team", "User in another team", 409),
    /** {@code /errors/user-not-found}, status 404. */
    USER_NOT_FOUND("user-not-found", "User not found", 404),
    /** {@code /errors/validation}, status 400. */
    VALIDATION("validation", "Invalid request", 400),
    /** {@code /errors/version-mismatch}, status 412. */
    VERSION_MISMATCH("version-mismatch", "Version mismatch", 412),
    /** {@code /errors/workflow-name-taken}, status 409. */
    WORKFLOW_NAME_TAKEN("workflow-name-taken", "Workflow name taken", 409);

    private static final String TYPE_PREFIX = "/errors/";

    private static final Map<String, ErrorCode> BY_SLUG =
            Stream.of(values()).collect(Collectors.toUnmodifiableMap(ErrorCode::slug, Function.identity()));

    private final String slug;
    private final String title;
    private final int status;

    ErrorCode(String slug, String title, int status) {
        this.slug = slug;
        this.title = title;
        this.status = status;
    }

    /**
     * Returns the stable slug, for example {@code not-found}.
     *
     * @return the slug
     */
    public String slug() {
        return slug;
    }

    /**
     * Returns the RFC 9457 {@code type} value, for example {@code /errors/not-found}.
     *
     * @return the problem type
     */
    public String type() {
        return TYPE_PREFIX + slug;
    }

    /**
     * Returns the problem title shown to clients.
     *
     * @return the title
     */
    public String title() {
        return title;
    }

    /**
     * Returns the HTTP status the LLD assigns to this code.
     *
     * @return the status, for example 404
     */
    public int status() {
        return status;
    }

    /**
     * Looks up a code by its slug.
     *
     * @param slug the slug, for example {@code not-found}
     * @return the code, or empty if the slug is unknown
     */
    public static Optional<ErrorCode> fromSlug(String slug) {
        return Optional.ofNullable(BY_SLUG.get(slug));
    }
}
