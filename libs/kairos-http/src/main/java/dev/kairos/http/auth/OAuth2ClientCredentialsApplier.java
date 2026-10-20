package dev.kairos.http.auth;

import dev.kairos.http.client.OutboundHttpClient;
import dev.kairos.http.request.HeaderField;
import dev.kairos.http.request.OutboundRequest;
import java.util.Objects;

/** {@code OAUTH2_CLIENT_CREDENTIALS}: {@code Authorization: Bearer <access token>} from {@link OAuth2TokenProvider}. */
final class OAuth2ClientCredentialsApplier implements AuthApplier {

    private final OAuth2TokenProvider tokens;

    OAuth2ClientCredentialsApplier(OAuth2TokenProvider tokens) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    @Override
    public AuthType type() {
        return AuthType.OAUTH2_CLIENT_CREDENTIALS;
    }

    @Override
    public OutboundRequest apply(OutboundRequest request, AuthMaterial material, OutboundHttpClient client) {
        if (!(material instanceof AuthMaterial.OAuth2ClientCredentials credentials)) {
            throw StaticAuthAppliers.wrongType(AuthType.OAUTH2_CLIENT_CREDENTIALS, material);
        }
        String token = tokens.token(credentials, client);
        return request.withHeader(new HeaderField(StaticAuthAppliers.AUTHORIZATION, "Bearer " + token));
    }
}
