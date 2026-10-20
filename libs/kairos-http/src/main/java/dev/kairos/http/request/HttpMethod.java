package dev.kairos.http.request;

/** The methods an endpoint may use (LLD section 3.5). */
public enum HttpMethod {
    /** {@code GET}. */
    GET,
    /** {@code POST}. */
    POST,
    /** {@code PUT}. */
    PUT,
    /** {@code PATCH}. */
    PATCH,
    /** {@code DELETE}. */
    DELETE,
    /** {@code HEAD}; the response has no body. */
    HEAD
}
