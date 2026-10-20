package dev.kairos.domain.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class StatusRulesTest {

    @ParameterizedTest
    @CsvSource({"1xx, 100, 199", "2xx, 200, 299", "5xx, 500, 599", "204, 204, 204", "100, 100, 100", "599, 599, 599"})
    void parsesClassesAndExactStatuses(String text, int from, int to) {
        StatusPattern pattern = StatusPattern.parse(text);

        assertThat(pattern).isEqualTo(new StatusPattern(from, to));
        assertThat(pattern).hasToString(text);
        assertThat(pattern.matches(from)).isTrue();
        assertThat(pattern.matches(to)).isTrue();
        assertThat(pattern.matches(from - 1)).isFalse();
        assertThat(pattern.matches(to + 1)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "2", "20", "2000", "2XX", "2x", "2x5", "0xx", "6xx", "600", "099", "abc", "2xy", "20x"})
    void rejectsMalformedPatterns(String text) {
        assertThatExceptionOfType(ValidationFailedException.class)
                .isThrownBy(() -> StatusPattern.parse(text))
                .satisfies(e -> assertThat(e.fieldErrors())
                        .containsExactly(new FieldError("successStatus", FieldError.FORMAT)));
    }

    @Test
    void patternRangeMustBeValid() {
        assertThatIllegalArgumentException().isThrownBy(() -> new StatusPattern(99, 200));
        assertThatIllegalArgumentException().isThrownBy(() -> new StatusPattern(300, 200));
        assertThatIllegalArgumentException().isThrownBy(() -> new StatusPattern(500, 600));
    }

    @Test
    void parsesTheJsonForm() {
        StatusRules rules = StatusRules.parse(List.of("2xx", "404"), Map.of("409", StatusOverride.SUCCESS));

        assertThat(rules.successStatus()).containsExactly(new StatusPattern(200, 299), new StatusPattern(404, 404));
        assertThat(rules.statusOverrides()).containsExactly(Map.entry(409, StatusOverride.SUCCESS));
        assertThat(rules.isListedAsSuccess(404)).isTrue();
        assertThat(rules.isListedAsSuccess(409)).isFalse();
    }

    @Test
    void defaultTreatsOnly2xxAsSuccess() {
        assertThat(StatusRules.DEFAULT.isListedAsSuccess(200)).isTrue();
        assertThat(StatusRules.DEFAULT.isListedAsSuccess(299)).isTrue();
        assertThat(StatusRules.DEFAULT.isListedAsSuccess(300)).isFalse();
        assertThat(StatusRules.DEFAULT.statusOverrides()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"40a, format", "4090, format", "40, format", "099, range", "600, range"})
    void rejectsMalformedOverrideKeys(String key, String code) {
        assertThatExceptionOfType(ValidationFailedException.class)
                .isThrownBy(() -> StatusRules.parse(List.of("2xx"), Map.of(key, StatusOverride.FAIL)))
                .satisfies(e -> assertThat(e.fieldErrors()).containsExactly(new FieldError("statusOverrides", code)));
    }

    @Test
    void enforcesSizeLimits() {
        List<String> tooManyPatterns = Collections.nCopies(StatusRules.MAX_SUCCESS_PATTERNS + 1, "2xx");
        Map<String, StatusOverride> tooManyOverrides = new HashMap<>();
        Map<Integer, StatusOverride> tooManyParsed = new HashMap<>();
        for (int status = 400; status <= 400 + StatusRules.MAX_OVERRIDES; status++) {
            tooManyOverrides.put(Integer.toString(status), StatusOverride.RETRY);
            tooManyParsed.put(status, StatusOverride.RETRY);
        }

        assertField(() -> StatusRules.parse(tooManyPatterns, Map.of()), "successStatus", FieldError.LENGTH);
        assertField(() -> StatusRules.parse(List.of("2xx"), tooManyOverrides), "statusOverrides", FieldError.LENGTH);
        assertField(
                () -> new StatusRules(
                        Collections.nCopies(StatusRules.MAX_SUCCESS_PATTERNS + 1, new StatusPattern(200, 299)),
                        Map.of()),
                "successStatus",
                FieldError.LENGTH);
        assertField(
                () -> new StatusRules(List.of(new StatusPattern(200, 299)), tooManyParsed),
                "statusOverrides",
                FieldError.LENGTH);
        assertField(() -> new StatusRules(List.of(), Map.of()), "successStatus", FieldError.REQUIRED);
        assertField(
                () -> new StatusRules(List.of(new StatusPattern(200, 299)), Map.of(700, StatusOverride.FAIL)),
                "statusOverrides",
                FieldError.RANGE);
    }

    private static void assertField(Runnable action, String field, String code) {
        assertThatExceptionOfType(ValidationFailedException.class)
                .isThrownBy(action::run)
                .satisfies(e -> assertThat(e.fieldErrors()).containsExactly(new FieldError(field, code)));
    }

    @Test
    void copiesItsInputs() {
        List<StatusPattern> patterns = new java.util.ArrayList<>(List.of(new StatusPattern(200, 299)));
        StatusRules rules = new StatusRules(patterns, Map.of());
        patterns.clear();

        assertThat(rules.successStatus()).hasSize(1);
    }
}
