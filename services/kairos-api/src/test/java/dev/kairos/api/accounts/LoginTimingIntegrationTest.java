package dev.kairos.api.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.api.ApiApplicationTest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Login timing benchmark (IMPLEMENTATION_CHECKLIST T-202): a login with an unknown email and one with a known email
 * and a wrong password take the same time within 20%, so response times do not reveal which emails are registered.
 *
 * <p>Each attempt uses a fresh account and client address, so neither the rate limits nor the lockout interfere. The
 * two kinds alternate, after a warm-up, and the medians are compared.
 */
@TestPropertySource(properties = "kairos.api.trusted-proxies=127.0.0.1/32,::1/128")
class LoginTimingIntegrationTest extends ApiApplicationTest {

    private static final int WARM_UP = 5;
    private static final int SAMPLES = 25;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void unknownAndKnownEmailsTakeTheSameTimeWithinTwentyPercent() {
        List<String> known = new ArrayList<>();
        for (int i = 0; i < WARM_UP + SAMPLES; i++) {
            String email = AccountsIntegrationTest.newEmail();
            HttpResponse<String> created =
                    post("/v1/auth/signup", Map.of("email", email, "password", AccountsIntegrationTest.password()));
            assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
            known.add(email);
        }
        List<Long> unknownNanos = new ArrayList<>();
        List<Long> knownNanos = new ArrayList<>();
        for (int i = 0; i < WARM_UP + SAMPLES; i++) {
            long unknown = timeFailedLogin(AccountsIntegrationTest.newEmail());
            long existing = timeFailedLogin(known.get(i));
            if (i >= WARM_UP) {
                unknownNanos.add(unknown);
                knownNanos.add(existing);
            }
        }

        double unknownMedian = median(unknownNanos);
        double knownMedian = median(knownNanos);
        assertThat(Math.abs(unknownMedian - knownMedian) / knownMedian)
                .as("median login time unknown=%.1fms known=%.1fms", unknownMedian / 1e6, knownMedian / 1e6)
                .isLessThan(0.20);
    }

    private long timeFailedLogin(String email) {
        long start = System.nanoTime();
        HttpResponse<String> response =
                post("/v1/auth/login", Map.of("email", email, "password", "wrong-password-123"));
        long elapsed = System.nanoTime() - start;
        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        return elapsed;
    }

    private HttpResponse<String> post(String path, Map<String, String> body) {
        return send(
                "POST", path, null, json.writeValueAsString(body), "X-Forwarded-For", AccountsIntegrationTest.newIp());
    }

    private static double median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }
}
