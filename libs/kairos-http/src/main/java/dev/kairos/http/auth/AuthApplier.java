package dev.kairos.http.auth;

import dev.kairos.http.client.OutboundHttpClient;
import dev.kairos.http.request.OutboundRequest;

/**
 * Applies one kind of target authentication to a rendered request (strategy, one implementation per {@link AuthType}).
 *
 * <p>Implementations replace any header of the same name the endpoint's default headers carried, so a template can
 * never send two credentials. They never log or keep secrets.
 */
public interface AuthApplier {

    /**
     * Returns the auth type this strategy handles.
     *
     * @return the type
     */
    AuthType type();

    /**
     * Returns the request with credentials applied.
     *
     * @param request the rendered request
     * @param material the auth settings and secret; its {@link AuthMaterial#type()} is {@link #type()}
     * @param client the client the call will use; OAuth 2.0 fetches its token through it
     * @return the authenticated request
     * @throws AuthFailedException if credentials cannot be obtained
     * @throws IllegalArgumentException if the material is of another type
     */
    OutboundRequest apply(OutboundRequest request, AuthMaterial material, OutboundHttpClient client);
}
