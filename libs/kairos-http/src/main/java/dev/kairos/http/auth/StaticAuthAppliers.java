package dev.kairos.http.auth;

import dev.kairos.http.client.OutboundHttpClient;
import dev.kairos.http.request.HeaderField;
import dev.kairos.http.request.OutboundRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** The strategies that need no network call: {@code NONE}, {@code BASIC}, {@code BEARER} and {@code API_KEY}. */
final class StaticAuthAppliers {

    static final String AUTHORIZATION = "Authorization";

    private StaticAuthAppliers() {}

    static List<AuthApplier> all() {
        return List.of(new NoAuth(), new BasicAuth(), new BearerAuth(), new ApiKeyAuth());
    }

    static IllegalArgumentException wrongType(AuthType expected, AuthMaterial material) {
        return new IllegalArgumentException("expected " + expected + " material, got " + material.type());
    }

    /** {@code NONE}: the request is sent as rendered. */
    static final class NoAuth implements AuthApplier {

        @Override
        public AuthType type() {
            return AuthType.NONE;
        }

        @Override
        public OutboundRequest apply(OutboundRequest request, AuthMaterial material, OutboundHttpClient client) {
            if (!(material instanceof AuthMaterial.None)) {
                throw wrongType(AuthType.NONE, material);
            }
            return request;
        }
    }

    /** {@code BASIC}: {@code Authorization: Basic base64(user:password)} in UTF-8 (RFC 7617). */
    static final class BasicAuth implements AuthApplier {

        @Override
        public AuthType type() {
            return AuthType.BASIC;
        }

        @Override
        public OutboundRequest apply(OutboundRequest request, AuthMaterial material, OutboundHttpClient client) {
            if (!(material instanceof AuthMaterial.Basic basic)) {
                throw wrongType(AuthType.BASIC, material);
            }
            String encoded = Base64.getEncoder()
                    .encodeToString((basic.username() + ":" + basic.password()).getBytes(StandardCharsets.UTF_8));
            return request.withHeader(new HeaderField(AUTHORIZATION, "Basic " + encoded));
        }
    }

    /** {@code BEARER}: {@code Authorization: Bearer <token>} (RFC 6750). */
    static final class BearerAuth implements AuthApplier {

        @Override
        public AuthType type() {
            return AuthType.BEARER;
        }

        @Override
        public OutboundRequest apply(OutboundRequest request, AuthMaterial material, OutboundHttpClient client) {
            if (!(material instanceof AuthMaterial.Bearer bearer)) {
                throw wrongType(AuthType.BEARER, material);
            }
            return request.withHeader(new HeaderField(AUTHORIZATION, "Bearer " + bearer.token()));
        }
    }

    /** {@code API_KEY}: the key in the named header, or appended as a percent-encoded query parameter. */
    static final class ApiKeyAuth implements AuthApplier {

        @Override
        public AuthType type() {
            return AuthType.API_KEY;
        }

        @Override
        public OutboundRequest apply(OutboundRequest request, AuthMaterial material, OutboundHttpClient client) {
            if (!(material instanceof AuthMaterial.ApiKey apiKey)) {
                throw wrongType(AuthType.API_KEY, material);
            }
            return switch (apiKey.location()) {
                case HEADER -> request.withHeader(new HeaderField(apiKey.name(), apiKey.key()));
                case QUERY -> request.withQueryParameter(apiKey.name(), apiKey.key());
            };
        }
    }
}
