package dev.kairos.http.auth;

/** How a target is authenticated ({@code auth.type}, LLD section 3.5). */
public enum AuthType {
    /** No credentials. */
    NONE,
    /** HTTP Basic with a username and password. */
    BASIC,
    /** A static bearer token. */
    BEARER,
    /** A static key in a named header or query parameter. */
    API_KEY,
    /** An OAuth 2.0 access token from the client-credentials grant. */
    OAUTH2_CLIENT_CREDENTIALS
}
