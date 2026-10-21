package dev.kairos.api;

import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import dev.kairos.security.token.AccessTokenService;
import dev.kairos.testsupport.KairosIntegrationTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base of the tests that start the whole API on a random port against the shared Postgres and Redis containers, with
 * the {@code local} profile and the secrets of {@link ApiTestFixtures}. Requests go over real HTTP, so every filter,
 * the security chain, the error dispatch and the message converters take part.
 */
@KairosIntegrationTest(kafka = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles("local")
public abstract class ApiApplicationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected AccessTokenService tokens;

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        ApiTestFixtures.register(registry);
    }

    /**
     * Sends a request.
     *
     * @param method the HTTP method
     * @param path the path and query
     * @param bearer the bearer credential, or {@code null}
     * @param body a JSON body, or {@code null}
     * @param headers extra header names and values, alternating
     * @return the response
     */
    protected HttpResponse<String> send(
            String method, String path, @Nullable String bearer, @Nullable String body, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Creates a user with an active session and returns an access token for it.
     *
     * @param team the user's team, or {@code null}
     * @return the user and the token
     */
    protected Caller newUser(@Nullable TeamId team) {
        UserId user = ApiTestFixtures.insertUser(team);
        UUID session = ApiTestFixtures.insertSession(user);
        String token = tokens.issue(user, team, session).token().reveal();
        return new Caller(user, team, session, token);
    }

    /**
     * A user with a token.
     *
     * @param user the user
     * @param team the team, or {@code null}
     * @param sessionId the session family
     * @param token the access token
     */
    protected record Caller(UserId user, @Nullable TeamId team, UUID sessionId, String token) {}
}
