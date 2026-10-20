package dev.kairos.http.auth;

import dev.kairos.http.client.OutboundHttpClient;
import dev.kairos.http.request.OutboundRequest;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Picks the {@link AuthApplier} for an endpoint's auth type and applies it. Holds one strategy per {@link AuthType}.
 *
 * <p>Thread-safe.
 */
public final class Authenticator {

    private final Map<AuthType, AuthApplier> appliers;
    private final OAuth2TokenProvider tokens;

    /**
     * Creates the authenticator with every built-in strategy.
     *
     * @param tokens the OAuth 2.0 token cache
     */
    public Authenticator(OAuth2TokenProvider tokens) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        Map<AuthType, AuthApplier> byType = new EnumMap<>(AuthType.class);
        for (AuthApplier applier : StaticAuthAppliers.all()) {
            byType.put(applier.type(), applier);
        }
        byType.put(AuthType.OAUTH2_CLIENT_CREDENTIALS, new OAuth2ClientCredentialsApplier(tokens));
        this.appliers = Map.copyOf(byType);
    }

    /**
     * Applies the credentials to a request.
     *
     * @param request the rendered request
     * @param material the auth settings and decrypted secret
     * @param client the client the call will use
     * @return the authenticated request
     * @throws AuthFailedException if credentials cannot be obtained
     */
    public OutboundRequest apply(OutboundRequest request, AuthMaterial material, OutboundHttpClient client) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(client, "client");
        AuthApplier applier = appliers.get(material.type());
        if (applier == null) {
            throw new IllegalStateException("no strategy for " + material.type());
        }
        return applier.apply(request, material, client);
    }

    /**
     * Reacts to a 401 from the target: a cached OAuth 2.0 token is dropped, so the next attempt fetches a new one.
     * Other auth types have nothing to refresh.
     *
     * @param material the material the rejected call used
     */
    public void onUnauthorized(AuthMaterial material) {
        if (material instanceof AuthMaterial.OAuth2ClientCredentials credentials) {
            tokens.invalidate(credentials.cacheKey());
        }
    }
}
