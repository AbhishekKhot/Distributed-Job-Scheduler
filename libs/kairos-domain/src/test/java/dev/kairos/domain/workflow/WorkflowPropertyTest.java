package dev.kairos.domain.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.model.TaskKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

class WorkflowPropertyTest {
    @Property(tries = 300)
    void randomLayeredDagsPreserveEveryDependency(@ForAll("ranks") List<Integer> ranks, @ForAll int seed) {
        List<WorkflowTask> tasks = new ArrayList<>();
        List<WorkflowSpec.Edge> edges = new ArrayList<>();
        for (int i = 0; i < ranks.size(); i++) {
            tasks.add(WorkflowValidatorTest.task("task" + i));
            for (int j = 0; j < ranks.size(); j++) {
                if (ranks.get(i) < ranks.get(j) && Math.floorMod(seed + i * 31 + j * 7, 4) == 0) {
                    edges.add(WorkflowValidatorTest.edge("task" + i, "task" + j));
                }
            }
        }
        WorkflowPlan plan = WorkflowValidator.validate(new WorkflowSpec(tasks, edges));
        Map<TaskKey, Integer> level = new HashMap<>();
        for (int i = 0; i < plan.levels().size(); i++)
            for (TaskKey key : plan.levels().get(i)) level.put(key, i);
        assertThat(level).hasSize(tasks.size());
        for (var edge : edges) {
            assertThat(level.get(edge.from())).isLessThan(level.get(edge.to()));
            assertThat(plan.ancestors().get(edge.to())).contains(edge.from());
        }
        for (TaskKey root : plan.roots())
            assertThat(edges).noneMatch(edge -> edge.to().equals(root));
    }

    @Property(tries = 100)
    void closingAReachablePathAlwaysFailsAsACycle(@ForAll @IntRange(min = 2, max = 10) int length) {
        WorkflowSpec chain = WorkflowValidatorTest.chain(length);
        List<WorkflowSpec.Edge> edges = new ArrayList<>(chain.edges());
        edges.add(WorkflowValidatorTest.edge("task" + (length - 1), "task0"));
        assertThatThrownBy(() -> WorkflowValidator.validate(new WorkflowSpec(chain.tasks(), edges)))
                .isInstanceOfSatisfying(
                        ValidationFailedException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CYCLE_DETECTED));
    }

    @Provide
    Arbitrary<List<Integer>> ranks() {
        return Arbitraries.integers().between(0, 4).list().ofMinSize(1).ofMaxSize(40);
    }
}
