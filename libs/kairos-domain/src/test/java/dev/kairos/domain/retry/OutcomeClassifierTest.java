package dev.kairos.domain.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.kairos.domain.model.ErrorClass;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class OutcomeClassifierTest {

    private static final Classification SUCCESS = new Classification.Success();

    /** Every status class, with its edges and the statuses that have their own rule. */
    @ParameterizedTest(name = "{0} -> {1} {2}")
    @CsvSource({
        "100, HTTP_4XX, NON_RETRYABLE",
        "101, HTTP_4XX, NON_RETRYABLE",
        "199, HTTP_4XX, NON_RETRYABLE",
        "200, , ",
        "201, , ",
        "204, , ",
        "299, , ",
        "300, HTTP_4XX, NON_RETRYABLE",
        "301, HTTP_4XX, NON_RETRYABLE",
        "302, HTTP_4XX, NON_RETRYABLE",
        "304, HTTP_4XX, NON_RETRYABLE",
        "399, HTTP_4XX, NON_RETRYABLE",
        "400, HTTP_4XX, NON_RETRYABLE",
        "401, HTTP_4XX, NON_RETRYABLE",
        "403, HTTP_4XX, NON_RETRYABLE",
        "404, HTTP_4XX, NON_RETRYABLE",
        "408, HTTP_4XX, RETRYABLE",
        "409, HTTP_4XX, NON_RETRYABLE",
        "422, HTTP_4XX, NON_RETRYABLE",
        "425, HTTP_4XX, RETRYABLE",
        "429, HTTP_429, RETRYABLE",
        "499, HTTP_4XX, NON_RETRYABLE",
        "500, HTTP_5XX, RETRYABLE",
        "501, HTTP_5XX, RETRYABLE",
        "502, HTTP_5XX, RETRYABLE",
        "503, HTTP_5XX, RETRYABLE",
        "504, HTTP_5XX, RETRYABLE",
        "599, HTTP_5XX, RETRYABLE"
    })
    void classifiesEveryStatusClassWithDefaultRules(int status, ErrorClass errorClass, FailureKind kind) {
        Classification expected = errorClass == null ? SUCCESS : new Classification.Failure(errorClass, kind);

        assertThat(OutcomeClassifier.classify(new CallResult.Responded(status), StatusRules.DEFAULT))
                .isEqualTo(expected);
    }

    /** Overrides win over the success list and the defaults, in every direction. */
    @ParameterizedTest(name = "{0} overridden to {1}")
    @CsvSource({
        "409, SUCCESS, , ",
        "404, SUCCESS, , ",
        "503, SUCCESS, , ",
        "302, SUCCESS, , ",
        "404, RETRY, HTTP_4XX, RETRYABLE",
        "302, RETRY, HTTP_4XX, RETRYABLE",
        "200, RETRY, HTTP_4XX, RETRYABLE",
        "429, RETRY, HTTP_429, RETRYABLE",
        "500, FAIL, HTTP_5XX, NON_RETRYABLE",
        "429, FAIL, HTTP_429, NON_RETRYABLE",
        "408, FAIL, HTTP_4XX, NON_RETRYABLE",
        "200, FAIL, HTTP_4XX, NON_RETRYABLE"
    })
    void overridesWin(int status, StatusOverride override, ErrorClass errorClass, FailureKind kind) {
        StatusRules rules = new StatusRules(List.of(new StatusPattern(200, 299)), Map.of(status, override));
        Classification expected = errorClass == null ? SUCCESS : new Classification.Failure(errorClass, kind);

        assertThat(OutcomeClassifier.classifyStatus(status, rules)).isEqualTo(expected);
    }

    @Test
    void customSuccessListReplacesThe2xxDefault() {
        StatusRules rules = StatusRules.parse(List.of("200", "404"), Map.of());

        assertThat(OutcomeClassifier.classifyStatus(200, rules)).isEqualTo(SUCCESS);
        assertThat(OutcomeClassifier.classifyStatus(404, rules)).isEqualTo(SUCCESS);
        assertThat(OutcomeClassifier.classifyStatus(204, rules))
                .isEqualTo(new Classification.Failure(ErrorClass.HTTP_4XX, FailureKind.NON_RETRYABLE));
        assertThat(OutcomeClassifier.classifyStatus(503, rules))
                .isEqualTo(new Classification.Failure(ErrorClass.HTTP_5XX, FailureKind.RETRYABLE));
    }

    static Stream<Arguments> errorTypes() {
        return Stream.of(
                Arguments.of(new CallResult.TimedOut(true), ErrorClass.TIMEOUT, FailureKind.UNKNOWN_OUTCOME),
                Arguments.of(new CallResult.TimedOut(false), ErrorClass.CONNECT_FAILED, FailureKind.RETRYABLE),
                Arguments.of(
                        new CallResult.ConnectionFailed(true), ErrorClass.UNKNOWN_OUTCOME, FailureKind.UNKNOWN_OUTCOME),
                Arguments.of(new CallResult.ConnectionFailed(false), ErrorClass.CONNECT_FAILED, FailureKind.RETRYABLE),
                Arguments.of(new CallResult.DnsFailed(), ErrorClass.DNS_FAILED, FailureKind.RETRYABLE),
                Arguments.of(new CallResult.TlsFailed(), ErrorClass.TLS_FAILED, FailureKind.RETRYABLE),
                Arguments.of(
                        new CallResult.TargetNotAllowed(), ErrorClass.TARGET_NOT_ALLOWED, FailureKind.NON_RETRYABLE),
                Arguments.of(new CallResult.RenderFailed(), ErrorClass.RENDER_FAILED, FailureKind.NON_RETRYABLE),
                Arguments.of(
                        new CallResult.ResponseTooLarge(200), ErrorClass.RESPONSE_TOO_LARGE, FailureKind.NON_RETRYABLE),
                Arguments.of(new CallResult.ResponseTooLarge(503), ErrorClass.HTTP_5XX, FailureKind.RETRYABLE),
                Arguments.of(new CallResult.ResponseTooLarge(404), ErrorClass.HTTP_4XX, FailureKind.NON_RETRYABLE),
                Arguments.of(new CallResult.OutputMissing(201), ErrorClass.OUTPUT_MISSING, FailureKind.NON_RETRYABLE),
                Arguments.of(new CallResult.OutputMissing(429), ErrorClass.HTTP_429, FailureKind.RETRYABLE),
                Arguments.of(new CallResult.OutputMissing(400), ErrorClass.HTTP_4XX, FailureKind.NON_RETRYABLE));
    }

    @ParameterizedTest(name = "{0} -> {1} {2}")
    @MethodSource("errorTypes")
    void classifiesEveryErrorType(CallResult result, ErrorClass errorClass, FailureKind kind) {
        assertThat(OutcomeClassifier.classify(result, StatusRules.DEFAULT))
                .isEqualTo(new Classification.Failure(errorClass, kind));
    }

    @Test
    void errorTypeTableCoversEveryKindOfCallResult() {
        Set<Class<?>> covered =
                errorTypes().map(arguments -> arguments.get()[0].getClass()).collect(Collectors.toSet());
        covered.add(CallResult.Responded.class);

        assertThat(covered).containsExactlyInAnyOrder(CallResult.class.getPermittedSubclasses());
    }

    @Test
    void unknownOutcomeAppliesExactlyWhenTheRequestMayHaveBeenSent() {
        List<CallResult> sent = List.of(new CallResult.TimedOut(true), new CallResult.ConnectionFailed(true));
        List<CallResult> notSent = List.of(
                new CallResult.TimedOut(false),
                new CallResult.ConnectionFailed(false),
                new CallResult.DnsFailed(),
                new CallResult.TlsFailed(),
                new CallResult.TargetNotAllowed(),
                new CallResult.RenderFailed());

        assertThat(sent).allSatisfy(r -> assertThat(kindOf(r)).isEqualTo(FailureKind.UNKNOWN_OUTCOME));
        assertThat(notSent).allSatisfy(r -> assertThat(kindOf(r)).isNotEqualTo(FailureKind.UNKNOWN_OUTCOME));
    }

    private static FailureKind kindOf(CallResult result) {
        return ((Classification.Failure) OutcomeClassifier.classify(result, StatusRules.DEFAULT)).kind();
    }

    @Test
    void successOverrideAlsoAppliesToOversizedResponses() {
        StatusRules rules = new StatusRules(List.of(new StatusPattern(200, 299)), Map.of(409, StatusOverride.SUCCESS));

        assertThat(OutcomeClassifier.classify(new CallResult.ResponseTooLarge(409), rules))
                .isEqualTo(new Classification.Failure(ErrorClass.RESPONSE_TOO_LARGE, FailureKind.NON_RETRYABLE));
    }

    @Property
    void everyValidStatusIsClassifiedAndOnly2xxSucceedsByDefault(@ForAll @IntRange(min = 100, max = 599) int status) {
        Classification classification = OutcomeClassifier.classifyStatus(status, StatusRules.DEFAULT);

        assertThat(classification instanceof Classification.Success).isEqualTo(status >= 200 && status <= 299);
        if (classification instanceof Classification.Failure failure) {
            assertThat(failure.kind()).isNotEqualTo(FailureKind.UNKNOWN_OUTCOME);
            assertThat(failure.errorClass()).isIn(ErrorClass.HTTP_4XX, ErrorClass.HTTP_429, ErrorClass.HTTP_5XX);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 99, 600, 1000})
    void rejectsImpossibleStatuses(int status) {
        assertThatIllegalArgumentException().isThrownBy(() -> new CallResult.Responded(status));
        assertThatIllegalArgumentException().isThrownBy(() -> new CallResult.ResponseTooLarge(status));
        assertThatIllegalArgumentException().isThrownBy(() -> new CallResult.OutputMissing(status));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OutcomeClassifier.classifyStatus(status, StatusRules.DEFAULT));
    }
}
