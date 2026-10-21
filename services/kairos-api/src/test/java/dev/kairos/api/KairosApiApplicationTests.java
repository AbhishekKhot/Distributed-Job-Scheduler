package dev.kairos.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** Verifies that the application starts against the real databases and Redis, with its health endpoints open. */
class KairosApiApplicationTests extends ApiApplicationTest {

    @Test
    void contextLoadsAndProtectsTheApi() {
        HttpResponse<String> response = send("GET", "/v1/jobs", null, null);

        assertThat(response.statusCode()).isEqualTo(401);
    }
}
