package dev.kairos.api.endpoints.infrastructure;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import dev.kairos.security.token.AccessTokenService;
import java.util.UUID;

/** Issues short-lived audience-restricted platform tokens for the executor test route. */
public final class EndpointTestTokenIssuer {
    private final AccessTokenService tokens;
    private final KairosIdFactory ids;

    public EndpointTestTokenIssuer(AccessTokenService tokens, KairosIdFactory ids) {
        this.tokens = tokens;
        this.ids = ids;
    }

    public String issue(TeamId team) {
        return tokens.issue(new UserId(ids.newId()), team, UUID.randomUUID())
                .token()
                .reveal();
    }
}
