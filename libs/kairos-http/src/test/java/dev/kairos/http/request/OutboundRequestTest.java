package dev.kairos.http.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OutboundRequestTest {

    private static final URI URL = URI.create("https://orders.internal/v1/orders?x=1");

    @Test
    void buildsAnImmutableRequest() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        OutboundRequest request = OutboundRequest.builder(HttpMethod.POST, URL, Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Trace", "abc")
                .body(body)
                .build();
        body[0] = 'x';

        assertThat(request.method()).isEqualTo(HttpMethod.POST);
        assertThat(request.url()).isEqualTo(URL);
        assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(request.header("content-type")).contains("application/json");
        assertThat(request.header("missing")).isEmpty();
        assertThat(new String(request.body(), StandardCharsets.UTF_8)).isEqualTo("{}");
        assertThat(request.bodyLength()).isEqualTo(2);
        request.body()[0] = 'y';
        assertThat(new String(request.body(), StandardCharsets.UTF_8)).isEqualTo("{}");
        assertThatThrownBy(() -> request.headers().add(new HeaderField("a", "b")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toStringHidesPathQueryHeadersAndBody() {
        OutboundRequest request = OutboundRequest.builder(HttpMethod.GET, URL, Duration.ofSeconds(1))
                .header("Authorization", "Bearer secret-token")
                .build();

        assertThat(request.toString())
                .contains("orders.internal")
                .doesNotContain("secret-token")
                .doesNotContain("x=1");
        assertThat(new HeaderField("Authorization", "Bearer secret-token").toString())
                .doesNotContain("secret-token");
    }

    @Test
    void withHeaderReplacesHeadersOfTheSameName() {
        OutboundRequest request = OutboundRequest.builder(HttpMethod.GET, URL, Duration.ofSeconds(1))
                .header("authorization", "Bearer a")
                .header("X-Other", "1")
                .build()
                .withHeader(new HeaderField("Authorization", "Bearer b"));

        assertThat(request.headers()).extracting(HeaderField::name).containsExactly("X-Other", "Authorization");
        assertThat(request.header("Authorization")).contains("Bearer b");
    }

    @Test
    void withQueryParameterEncodesNameAndValue() {
        OutboundRequest plain = OutboundRequest.builder(
                        HttpMethod.GET, URI.create("https://orders.internal/v1"), Duration.ofSeconds(1))
                .build();

        assertThat(plain.withQueryParameter("api key", "a&b=c/d é").url().toString())
                .isEqualTo("https://orders.internal/v1?api%20key=a%26b%3Dc%2Fd%20%C3%A9");
        assertThat(OutboundRequest.builder(HttpMethod.GET, URL, Duration.ofSeconds(1))
                        .build()
                        .withQueryParameter("k", "v-._~")
                        .url()
                        .toString())
                .isEqualTo("https://orders.internal/v1/orders?x=1&k=v-._~");
        assertThatThrownBy(() -> plain.withQueryParameter("", "v")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Host", "content-length", "Transfer-Encoding", "Connection", "Expect", "TE", "Upgrade"})
    void refusesHeadersTheClientManages(String name) {
        OutboundRequest.Builder builder = OutboundRequest.builder(HttpMethod.GET, URL, Duration.ofSeconds(1))
                .header(name, "x");

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\r\nX-Injected: 1", "a\nb", "a\rb", "nul\0", "del\u007f", "wide "})
    void refusesControlCharactersInHeaderValues(String value) {
        assertThatThrownBy(() -> new HeaderField("X-Test", value)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "X Test", "X-Test\r\n", "X:Test", "Ä"})
    void refusesInvalidHeaderNames(String name) {
        assertThatThrownBy(() -> new HeaderField(name, "v")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTabsAndLatinOneInHeaderValues() {
        assertThat(new HeaderField("X-Test", "a\tb é").lowerName()).isEqualTo("x-test");
        assertThatThrownBy(() -> new HeaderField("X-Test", "v".repeat(HeaderField.MAX_VALUE + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesTimeoutBodyAndHeaderLimits() {
        assertThatThrownBy(() -> OutboundRequest.builder(HttpMethod.GET, URL, Duration.ofMillis(999))
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboundRequest.builder(HttpMethod.GET, URL, Duration.ofMinutes(6))
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboundRequest.builder(HttpMethod.POST, URL, Duration.ofSeconds(1))
                        .body(new byte[OutboundRequest.MAX_BODY_BYTES + 1])
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        OutboundRequest.Builder many = OutboundRequest.builder(HttpMethod.GET, URL, Duration.ofSeconds(1));
        for (int i = 0; i <= OutboundRequest.MAX_HEADERS; i++) {
            many.header("X-" + i, "v");
        }
        assertThatThrownBy(many::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresAnAbsoluteHierarchicalUrl() {
        assertThatThrownBy(() -> OutboundRequest.builder(HttpMethod.GET, URI.create("/v1"), Duration.ofSeconds(1))
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboundRequest.builder(
                                HttpMethod.GET, URI.create("mailto:a@b.internal"), Duration.ofSeconds(1))
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void percentEncodesUtf8() {
        assertThat(OutboundRequest.percentEncode("AZaz09-._~ /?#[]@!$&'()*+,;=%"))
                .isEqualTo("AZaz09-._~%20%2F%3F%23%5B%5D%40%21%24%26%27%28%29%2A%2B%2C%3B%3D%25");
    }
}
