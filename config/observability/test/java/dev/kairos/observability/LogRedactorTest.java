package dev.kairos.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LogRedactorTest {

    private static final String SECRET = "s3cr3t-Value_123";

    @ParameterizedTest
    @ValueSource(
            strings = {
                "password=" + SECRET,
                "PASSWORD=" + SECRET,
                "PassWord = " + SECRET,
                "db_password: " + SECRET,
                "jdbc:postgresql://db/kairos?user=app&password=" + SECRET + "&ssl=true",
                "{\"password\":\"" + SECRET + "\"}",
                "{ \"Password\"  :   \"" + SECRET + "\" }",
                "{\\\"password\\\":\\\"" + SECRET + "\\\"}",
                "{'clientSecret': '" + SECRET + "'}",
                "refresh_token=" + SECRET,
                "accessToken: " + SECRET,
                "X-Api-Key: " + SECRET,
                "api_key=" + SECRET,
                "apikey=" + SECRET,
                "Authorization: Bearer " + SECRET,
                "authorization=Basic " + SECRET,
                "calling target with Bearer " + SECRET + "abcdef",
                "https://kairos:" + SECRET + "@internal.example:8443/hook",
                "pwd=" + SECRET
            })
    void masksCredentials(String text) {
        String redacted = LogRedactor.redact(text);

        assertThat(redacted).doesNotContain(SECRET).contains(LogRedactor.MASK);
    }

    @Test
    void keepsTheKeyAndTheJsonShape() {
        assertThat(LogRedactor.redact("{\"password\" : \"" + SECRET + "\", \"user\":\"asha\"}"))
                .isEqualTo("{\"password\" : \"***\", \"user\":\"asha\"}");
        assertThat(LogRedactor.redact("login failed password=" + SECRET + ", attempts=3"))
                .isEqualTo("login failed password=***, attempts=3");
    }

    @Test
    void masksQuotedValuesThatContainSpaces() {
        assertThat(LogRedactor.redact("secret=\"two words\" next")).isEqualTo("secret=\"***\" next");
    }

    @Test
    void masksTheSchemeCredentialAndKeepsTheScheme() {
        assertThat(LogRedactor.redact("sent Bearer abcdefghijklmnopqrstuvwxyz0123"))
                .isEqualTo("sent Bearer ***");
    }

    @Test
    void masksUrlUserInfoAndKeepsTheHost() {
        assertThat(LogRedactor.redact("connect https://user:" + SECRET + "@host:443/x"))
                .isEqualTo("connect https://user:***@host:443/x");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "run 0190f3c4-8d2a-7e01-9b55-2a6f1c0e4d10 settled in 120 ms",
                "Basic configuration loaded",
                "fetched 12 keys from the cache",
                "outbox relay published 500 rows"
            })
    void leavesOrdinaryTextAlone(String text) {
        assertThat(LogRedactor.redact(text)).isEqualTo(text);
    }

    @Test
    void passesNullAndEmptyThrough() {
        assertThat(LogRedactor.redact(null)).isNull();
        assertThat(LogRedactor.redact("")).isEmpty();
    }

    @Test
    void truncatesOversizedTextBeforeMatching() {
        String huge = "a".repeat(LogRedactor.MAX_LENGTH + 100) + " password=" + SECRET;

        String redacted = LogRedactor.redact(huge);

        assertThat(redacted).hasSize(LogRedactor.MAX_LENGTH + LogRedactor.TRUNCATED.length());
        assertThat(redacted).endsWith(LogRedactor.TRUNCATED).doesNotContain(SECRET);
    }

    @Test
    void finishesQuicklyOnPathologicalInput() {
        String pathological = ("token" + "-".repeat(47)).repeat(600) + "=";

        long started = System.nanoTime();
        LogRedactor.redact(pathological);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(2_000);
    }

    @ParameterizedTest
    @CsvSource({
        "password,true",
        "X-Api-Key,true",
        "api.key,true",
        "refresh_token,true",
        "Authorization,true",
        "clientSecret,true",
        "message,false",
        "runId,false",
        "'',false"
    })
    void recognisesSensitiveKeys(String key, boolean sensitive) {
        assertThat(LogRedactor.isSensitiveKey(key)).isEqualTo(sensitive);
    }

    @Test
    void treatsAbsentAndOverlongKeysAsNotSensitive() {
        assertThat(LogRedactor.isSensitiveKey(null)).isFalse();
        assertThat(LogRedactor.isSensitiveKey("token" + "x".repeat(300))).isFalse();
    }
}
