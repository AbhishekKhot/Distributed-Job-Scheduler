package dev.kairos.domain.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.model.EndpointName;
import dev.kairos.domain.model.TaskKey;
import dev.kairos.domain.template.JsonValue;
import dev.kairos.domain.template.OutputSpec;
import dev.kairos.domain.template.RequestTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WorkflowValidatorTest {
    @Test
    void diamondPlanHasStableLevelsCountersChildrenAndTransitiveAncestors() {
        WorkflowTask d = new WorkflowTask(
                key("D"),
                new EndpointName("test-target"),
                new RequestTemplate(Map.of(), Map.of(), Map.of("X-Id", "{{ tasks.A.outputs.id }}"), null),
                Map.of());
        WorkflowSpec spec = new WorkflowSpec(
                List.of(d, task("C"), task("A"), task("B")),
                List.of(edge("A", "C"), edge("C", "D"), edge("A", "B"), edge("B", "D")));
        WorkflowPlan plan = WorkflowValidator.validate(spec);
        assertThat(plan.levels()).containsExactly(List.of(key("A")), List.of(key("B"), key("C")), List.of(key("D")));
        assertThat(plan.roots()).containsExactly(key("A"));
        assertThat(plan.remainingDependencies())
                .containsEntry(key("A"), 0)
                .containsEntry(key("B"), 1)
                .containsEntry(key("D"), 2);
        assertThat(plan.downstream().get(key("A"))).containsExactly(key("B"), key("C"));
        assertThat(plan.ancestors().get(key("D"))).containsExactlyInAnyOrder(key("A"), key("B"), key("C"));
        assertThat(spec.onTaskFailure()).isEqualTo(WorkflowSpec.FailurePolicy.BLOCK_FOR_REVIEW);
        assertThatThrownBy(() -> plan.levels().getFirst().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.downstream().get(key("A")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.ancestors().get(key("D")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.remainingDependencies().put(key("D"), 0))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void wideFanoutAndIndependentRootsFitWithinTwoHundredTasks() {
        List<WorkflowTask> tasks =
                IntStream.range(0, 200).mapToObj(i -> task("task" + i)).toList();
        List<WorkflowSpec.Edge> edges =
                IntStream.range(1, 200).mapToObj(i -> edge("task0", "task" + i)).toList();
        WorkflowPlan plan =
                WorkflowValidator.validate(new WorkflowSpec(tasks, edges, WorkflowSpec.FailurePolicy.FAIL_FAST));
        assertThat(plan.levels()).hasSize(2);
        assertThat(plan.levels().get(1)).hasSize(199);
        assertThat(WorkflowValidator.validate(new WorkflowSpec(tasks, List.of()))
                        .roots())
                .hasSize(200);
        rejects(
                new WorkflowSpec(
                        IntStream.range(0, 201).mapToObj(i -> task("task" + i)).toList(), List.of()),
                ErrorCode.TOO_MANY_TASKS);
    }

    @Test
    void acceptsTenLevelsAndRejectsEleven() {
        assertThat(WorkflowValidator.validate(chain(10)).levels()).hasSize(10);
        rejects(chain(11), ErrorCode.TOO_MANY_TASKS);
    }

    @Test
    void cyclesReportAClosedPathIncludingSelfLoopsAndUnreachableComponents() {
        assertThatThrownBy(() -> WorkflowValidator.validate(
                        new WorkflowSpec(List.of(task("login")), List.of(edge("login", "login")))))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessage("Tasks login -> login form a cycle.");
        assertThatThrownBy(() -> WorkflowValidator.validate(new WorkflowSpec(
                        List.of(task("login"), task("createOrder")),
                        List.of(edge("login", "createOrder"), edge("createOrder", "login")))))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessage("Tasks login -> createOrder -> login form a cycle.");
        rejects(
                new WorkflowSpec(List.of(task("root"), task("a"), task("b")), List.of(edge("a", "b"), edge("b", "a"))),
                ErrorCode.CYCLE_DETECTED);
    }

    @Test
    void rejectsEmptyGraphsUnknownTasksDuplicateKeysAndDuplicateEdges() {
        rejects(new WorkflowSpec(List.of(), List.of()), ErrorCode.VALIDATION);
        rejects(new WorkflowSpec(List.of(task("a"), task("a")), List.of()), ErrorCode.VALIDATION);
        rejects(new WorkflowSpec(List.of(task("a")), List.of(edge("a", "unknown"))), ErrorCode.VALIDATION);
        rejects(new WorkflowSpec(List.of(task("a")), List.of(edge("unknown", "a"))), ErrorCode.VALIDATION);
        rejects(
                new WorkflowSpec(List.of(task("a"), task("b")), List.of(edge("a", "b"), edge("a", "b"))),
                ErrorCode.VALIDATION);
    }

    @Test
    void placeholdersMustNameDeclaredOutputsOfAncestors() {
        WorkflowTask notify = withRequest(
                "notify",
                new RequestTemplate(Map.of(), Map.of(), Map.of("X-Id", "{{ tasks.payment.outputs.id }}"), null));
        assertThatThrownBy(
                        () -> WorkflowValidator.validate(new WorkflowSpec(List.of(task("payment"), notify), List.of())))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessage("notify references tasks.payment.outputs.id, which is not an upstream output.");
        rejects(
                new WorkflowSpec(
                        List.of(
                                task("payment"),
                                withRequest(
                                        "notify",
                                        new RequestTemplate(
                                                Map.of(),
                                                Map.of(),
                                                Map.of("X-Id", "{{ tasks.payment.outputs.unknown }}"),
                                                null))),
                        List.of(edge("payment", "notify"))),
                ErrorCode.INVALID_PLACEHOLDER);
        rejects(
                new WorkflowSpec(
                        List.of(withRequest(
                                "notify",
                                new RequestTemplate(
                                        Map.of(), Map.of(), Map.of("X-Id", "{{ tasks.notify.outputs.id }}"), null))),
                        List.of()),
                ErrorCode.INVALID_PLACEHOLDER);
    }

    @Test
    void sensitiveOutputsAreAllowedOnlyInHeaderAndBodyValues() {
        WorkflowTask source = new WorkflowTask(
                key("a"),
                new EndpointName("test-target"),
                RequestTemplate.empty(),
                Map.of("id", new OutputSpec(OutputSpec.Source.STATUS, "", true, true)));
        String expression = "{{ tasks.a.outputs.id }}";
        for (RequestTemplate request : List.of(
                new RequestTemplate(Map.of("id", expression), Map.of(), Map.of(), null),
                new RequestTemplate(Map.of(), Map.of("id", expression), Map.of(), null))) {
            rejects(
                    new WorkflowSpec(List.of(source, withRequest("b", request)), List.of(edge("a", "b"))),
                    ErrorCode.INVALID_PLACEHOLDER);
        }
        var safe = withRequest(
                "b",
                new RequestTemplate(
                        Map.of(),
                        Map.of(),
                        Map.of("Authorization", "Bearer " + expression),
                        JsonValue.of(Map.of("id", expression))));
        assertThat(WorkflowValidator.validate(new WorkflowSpec(List.of(source, safe), List.of(edge("a", "b"))))
                        .levels())
                .hasSize(2);
    }

    @Test
    void requestNamesCannotBeTemplatesOrOverrideTransportHeaders() {
        for (String header : List.of("Host", "Content-Length", "Connection", "bad\r\nheader")) {
            assertThatThrownBy(() -> new RequestTemplate(Map.of(), Map.of(), Map.of(header, "x"), null))
                    .isInstanceOf(ValidationFailedException.class);
        }
        assertThatThrownBy(() -> new RequestTemplate(Map.of("{{run.id}}", "x"), Map.of(), Map.of(), null))
                .isInstanceOf(ValidationFailedException.class);
        rejects(
                new WorkflowSpec(
                        List.of(withRequest(
                                "a",
                                new RequestTemplate(
                                        Map.of(), Map.of(), Map.of(), JsonValue.of(Map.of("{{run.id}}", "value"))))),
                        List.of()),
                ErrorCode.INVALID_PLACEHOLDER);
    }

    @Test
    void boundsTotalTemplateBytesAndOutputMetadata() {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 40; i++) headers.put("X-Header-" + i, "x".repeat(16384));
        rejects(
                new WorkflowSpec(
                        List.of(withRequest("a", new RequestTemplate(Map.of(), Map.of(), headers, null))), List.of()),
                ErrorCode.VALIDATION);
        assertThatThrownBy(() -> new WorkflowTask(
                        key("a"),
                        new EndpointName("test-target"),
                        RequestTemplate.empty(),
                        Map.of("invalid.name", new OutputSpec(OutputSpec.Source.STATUS, "", true, false))))
                .isInstanceOf(ValidationFailedException.class);
        List<WorkflowTask> mutable = new ArrayList<>(List.of(task("a")));
        WorkflowSpec spec = new WorkflowSpec(mutable, List.of());
        mutable.clear();
        assertThat(spec.tasks()).hasSize(1);
    }

    static WorkflowTask task(String name) {
        return new WorkflowTask(
                key(name),
                new EndpointName("test-target"),
                RequestTemplate.empty(),
                Map.of("id", new OutputSpec(OutputSpec.Source.STATUS, "", true, false)));
    }

    static WorkflowTask withRequest(String name, RequestTemplate request) {
        return new WorkflowTask(key(name), new EndpointName("test-target"), request, Map.of());
    }

    static TaskKey key(String name) {
        return new TaskKey(name);
    }

    static WorkflowSpec.Edge edge(String from, String to) {
        return new WorkflowSpec.Edge(key(from), key(to));
    }

    static WorkflowSpec chain(int size) {
        return new WorkflowSpec(
                IntStream.range(0, size).mapToObj(i -> task("task" + i)).toList(),
                IntStream.range(1, size)
                        .mapToObj(i -> edge("task" + (i - 1), "task" + i))
                        .toList());
    }

    private static void rejects(WorkflowSpec spec, ErrorCode code) {
        assertThatThrownBy(() -> WorkflowValidator.validate(spec))
                .isInstanceOfSatisfying(
                        ValidationFailedException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(code));
    }
}
