package dev.kairos.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class EnumBehaviourTest {

    @Test
    void dstPoliciesCoverEveryCombinationOnce() {
        assertThat(Arrays.stream(DstPolicy.values()).map(p -> p.gap() + "/" + p.overlap()))
                .containsExactly("SHIFT_FORWARD/FIRST", "SHIFT_FORWARD/BOTH", "SKIP/FIRST", "SKIP/BOTH");
        assertThat(DstPolicy.DEFAULT.gap()).isEqualTo(DstPolicy.Gap.SHIFT_FORWARD);
        assertThat(DstPolicy.DEFAULT.overlap()).isEqualTo(DstPolicy.Overlap.FIRST);
    }

    @Test
    void runLatestIsTheDefaultMisfirePolicy() {
        assertThat(MisfirePolicy.DEFAULT).isEqualTo(MisfirePolicy.RUN_LATEST);
    }

    @Test
    void onlyRedrivenAndDiscardedEntriesAreResolved() {
        assertThat(Arrays.stream(DlqStatus.values()).filter(DlqStatus::isResolved))
                .containsExactly(DlqStatus.REDRIVEN, DlqStatus.DISCARDED);
    }

    @Test
    void finalWorkflowRunStatuses() {
        assertThat(Arrays.stream(WorkflowRunStatus.values()).filter(WorkflowRunStatus::isFinal))
                .containsExactly(WorkflowRunStatus.SUCCEEDED, WorkflowRunStatus.FAILED, WorkflowRunStatus.CANCELLED);
    }

    @Test
    void topicNamesMatchTheLld() {
        assertThat(Arrays.stream(OutboxTopic.values()).map(OutboxTopic::topicName))
                .containsExactly(
                        "kairos.run.high",
                        "kairos.run.medium",
                        "kairos.run.low",
                        "kairos.run.cancel",
                        "kairos.run.completed",
                        "kairos.run.dead");
    }
}
