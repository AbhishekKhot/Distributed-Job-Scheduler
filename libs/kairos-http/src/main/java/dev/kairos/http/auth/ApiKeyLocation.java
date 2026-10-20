package dev.kairos.http.auth;

/** Where an API key is sent. */
public enum ApiKeyLocation {
    /** In a request header. */
    HEADER,
    /** In a query parameter. */
    QUERY
}
