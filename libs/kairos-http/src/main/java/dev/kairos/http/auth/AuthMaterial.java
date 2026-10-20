package dev.kairos.http.auth;

import dev.kairos.http.request.HeaderField;
import dev.kairos.http.request.OutboundRequest;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An endpoint's auth settings together with the already-decrypted secret, ready to apply to one request.
 *
 * <p>Secrets are decrypted by the caller (T-109's {@code SecretCipher}) just for the call. No variant ever shows a
 * secret in {@link Object#toString()}; {@link #secrets()} lists the secret values so redaction can remove them from
 * errors and snippets. Every secret is limited to 8 KB of visible ASCII, so it cannot break a header.
 */
public sealed interface AuthMaterial {

    /** Longest accepted secret value. */
    int MAX_SECRET = 8192;

    /**
     * Returns the auth type.
     *
     * @return the type
     */
    AuthType type();

    /**
     * Returns the secret values, for redaction.
     *
     * @return the secrets, possibly empty
     */
    List<String> secrets();

    /** No credentials. */
    record None() implements AuthMaterial {

        @Override
        public AuthType type() {
            return AuthType.NONE;
        }

        @Override
        public List<String> secrets() {
            return List.of();
        }
    }

    /**
     * HTTP Basic credentials (RFC 7617).
     *
     * @param username the user name, without a colon
     * @param password the password
     */
    record Basic(String username, String password) implements AuthMaterial {

        /**
         * Validates the credentials.
         *
         * @throws IllegalArgumentException if a part is empty, too long, has a control character, or the user name
         *     has a colon
         */
        public Basic {
            requireSecret(username, "username");
            requireSecret(password, "password");
            if (username.indexOf(':') >= 0) {
                throw new IllegalArgumentException("a Basic user name has no colon");
            }
        }

        @Override
        public AuthType type() {
            return AuthType.BASIC;
        }

        @Override
        public List<String> secrets() {
            return List.of(password);
        }

        @Override
        public String toString() {
            return "Basic[<redacted>]";
        }
    }

    /**
     * A bearer token (RFC 6750).
     *
     * @param token the token
     */
    record Bearer(String token) implements AuthMaterial {

        /**
         * Validates the token.
         *
         * @throws IllegalArgumentException if it is empty, too long or has a space or control character
         */
        public Bearer {
            requireToken(token, "token");
        }

        @Override
        public AuthType type() {
            return AuthType.BEARER;
        }

        @Override
        public List<String> secrets() {
            return List.of(token);
        }

        @Override
        public String toString() {
            return "Bearer[<redacted>]";
        }
    }

    /**
     * A static API key sent in a header or a query parameter.
     *
     * @param location where the key goes
     * @param name the header or query parameter name
     * @param key the key
     */
    record ApiKey(ApiKeyLocation location, String name, String key) implements AuthMaterial {

        /**
         * Validates the parts.
         *
         * @throws IllegalArgumentException if the name is not a valid header token (or is managed by the client), or
         *     the key is empty, too long or has a space or control character
         */
        public ApiKey {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(name, "name");
            if (!HeaderField.isToken(name) || name.length() > 128) {
                throw new IllegalArgumentException("an API key name is a token of 1 to 128 characters");
            }
            if (location == ApiKeyLocation.HEADER && OutboundRequest.isManagedHeader(name)) {
                throw new IllegalArgumentException("an API key cannot use a header the client manages");
            }
            requireToken(key, "key");
        }

        @Override
        public AuthType type() {
            return AuthType.API_KEY;
        }

        @Override
        public List<String> secrets() {
            return List.of(key);
        }

        @Override
        public String toString() {
            return "ApiKey[location=" + location + ", name=" + name + ", key=<redacted>]";
        }
    }

    /**
     * OAuth 2.0 client credentials (RFC 6749 section 4.4). The token endpoint is called through the same validating
     * client as the target, so it must pass the target policy too.
     *
     * @param tokenUrl the token endpoint, HTTPS
     * @param scope the requested scope, or null
     * @param clientId the client id
     * @param clientSecret the client secret
     * @param cacheKey identity of the cached token
     */
    record OAuth2ClientCredentials(
            URI tokenUrl, @Nullable String scope, String clientId, String clientSecret, TokenCacheKey cacheKey)
            implements AuthMaterial {

        /**
         * Validates the parts.
         *
         * @throws IllegalArgumentException if a part is missing, too long or has a control character
         */
        public OAuth2ClientCredentials {
            Objects.requireNonNull(tokenUrl, "tokenUrl");
            Objects.requireNonNull(cacheKey, "cacheKey");
            requireSecret(clientId, "clientId");
            requireSecret(clientSecret, "clientSecret");
            if (scope != null && (scope.isEmpty() || scope.length() > 1024 || !HeaderField.isValidValue(scope))) {
                throw new IllegalArgumentException("a scope is 1 to 1024 characters without control characters");
            }
        }

        @Override
        public AuthType type() {
            return AuthType.OAUTH2_CLIENT_CREDENTIALS;
        }

        @Override
        public List<String> secrets() {
            return List.of(clientSecret);
        }

        @Override
        public String toString() {
            return "OAuth2ClientCredentials[cacheKey=" + cacheKey + ", clientSecret=<redacted>]";
        }
    }

    private static void requireSecret(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty() || value.length() > MAX_SECRET || !HeaderField.isValidValue(value)) {
            throw new IllegalArgumentException(
                    name + " is 1 to " + MAX_SECRET + " characters without control characters");
        }
    }

    private static void requireToken(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty() || value.length() > MAX_SECRET) {
            throw new IllegalArgumentException(name + " is 1 to " + MAX_SECRET + " characters");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                throw new IllegalArgumentException(name + " is visible ASCII without spaces");
            }
        }
    }
}
