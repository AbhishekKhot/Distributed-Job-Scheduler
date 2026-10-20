package dev.kairos.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResponseReaderTest {

    private final ResponseReader reader = new ResponseReader();

    @Test
    void readsUpToTheCap() throws IOException {
        byte[] exactly = new byte[ResponseReader.MAX_BODY_BYTES];

        ResponseReader.Result result = reader.read(new ByteArrayInputStream(exactly), "text/plain");

        assertThat(result).isInstanceOf(ResponseReader.Result.Complete.class);
        assertThat(((ResponseReader.Result.Complete) result).body().size()).isEqualTo(ResponseReader.MAX_BODY_BYTES);
    }

    @Test
    void stopsOneByteOverTheCap() throws IOException {
        byte[] tooMany = new byte[ResponseReader.MAX_BODY_BYTES + 1];

        assertThat(reader.read(new ByteArrayInputStream(tooMany), "text/plain"))
                .isInstanceOf(ResponseReader.Result.TooLarge.class);
    }

    @Test
    void decodesJsonOnlyForJsonContentTypes() throws IOException {
        byte[] json = "{\"a\":[1,2]}".getBytes(StandardCharsets.UTF_8);

        ResponseBody asJson = complete(reader.read(new ByteArrayInputStream(json), "application/json; charset=UTF-8"));
        ResponseBody asProblem = complete(reader.read(new ByteArrayInputStream(json), "application/problem+json"));
        ResponseBody asText = complete(reader.read(new ByteArrayInputStream(json), "text/plain"));
        ResponseBody untyped = complete(reader.read(new ByteArrayInputStream(json), null));

        assertThat(asJson.json()).isPresent();
        assertThat(asJson.json().get().at("/a/1").intValue()).isEqualTo(2);
        assertThat(asProblem.json()).isPresent();
        assertThat(asText.json()).isEmpty();
        assertThat(untyped.json()).isEmpty();
        assertThat(asText.text()).isEqualTo("{\"a\":[1,2]}");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{",
                "{\"a\":1,\"a\":2}",
                "{\"a\":1} trailing",
                "",
                "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[["
                        + "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[1]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]"
            })
    void malformedDuplicateOrTooDeepJsonHasNoTree(String body) {
        assertThat(reader.decodeJson(body.getBytes(StandardCharsets.UTF_8), "application/json"))
                .isNull();
    }

    @Test
    void theTreeIsACopy() throws IOException {
        ResponseBody body = complete(reader.read(
                new ByteArrayInputStream("{\"a\":1}".getBytes(StandardCharsets.UTF_8)), "application/json"));

        ((tools.jackson.databind.node.ObjectNode) body.json().get()).put("a", 2);

        assertThat(body.json().get().get("a").intValue()).isEqualTo(1);
        assertThat(body.toString()).isEqualTo("ResponseBody[size=7, json=true]");
        assertThat(ResponseBody.empty().size()).isZero();
    }

    @Test
    void recognisesJsonMediaTypes() {
        assertThat(ResponseReader.isJson("Application/JSON")).isTrue();
        assertThat(ResponseReader.isJson(" application/vnd.api+json ;q=1")).isTrue();
        assertThat(ResponseReader.isJson("text/json")).isFalse();
        assertThat(ResponseReader.isJson("application/jsonp")).isFalse();
        assertThat(ResponseReader.isJson(null)).isFalse();
    }

    @Test
    void aSmallerCapIsAllowed() throws IOException {
        ResponseReader small = new ResponseReader(4);

        assertThat(small.maxBytes()).isEqualTo(4);
        assertThat(small.read(new ByteArrayInputStream(new byte[5]), null))
                .isInstanceOf(ResponseReader.Result.TooLarge.class);
        assertThatThrownBy(() -> new ResponseReader(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResponseReader(ResponseReader.MAX_BODY_BYTES + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ResponseBody complete(ResponseReader.Result result) {
        assertThat(result).isInstanceOf(ResponseReader.Result.Complete.class);
        return ((ResponseReader.Result.Complete) result).body();
    }
}
