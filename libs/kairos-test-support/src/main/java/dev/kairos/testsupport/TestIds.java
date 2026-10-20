package dev.kairos.testsupport;

import dev.kairos.domain.id.DlqId;
import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.JobId;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import dev.kairos.domain.id.WorkflowId;
import dev.kairos.domain.id.WorkflowRunId;
import dev.kairos.domain.shard.Shard;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * Builds Kairos ids for tests, with the shard chosen by the test.
 *
 * <p>Ids come from the production {@link KairosIdFactory}, so they have the real layout: UUIDv7, the shard in the low
 * 8 bits and creation-time ordering. The static methods use one shared factory on the system clock; use
 * {@link #factory(Clock)} with a {@link MutableClock} when a test needs the timestamp bits under its control.
 *
 * <p>Thread-safe: the shared factory serialises id generation internally.
 */
public final class TestIds {

    private static final KairosIdFactory SHARED = factory(Clock.systemUTC());

    private TestIds() {}

    /**
     * Creates a factory on the given clock, for tests that control the timestamp of the ids.
     *
     * @param clock the time source for the timestamp field
     * @return a new factory backed by a fresh {@link SecureRandom}
     */
    public static KairosIdFactory factory(Clock clock) {
        return new KairosIdFactory(clock, new SecureRandom());
    }

    /**
     * Creates a raw id that carries the given shard.
     *
     * @param shard the shard, 0 to 255
     * @return a new id whose low 8 bits equal {@code shard}
     * @throws IllegalArgumentException if {@code shard} is out of range
     */
    public static UUID id(int shard) {
        return SHARED.newId(Shard.of(shard));
    }

    /**
     * Creates a team id. Team ids are not sharded.
     *
     * @return a new team id
     */
    public static TeamId teamId() {
        return SHARED.newTeamId();
    }

    /**
     * Creates a user id. User ids are not sharded.
     *
     * @return a new user id
     */
    public static UserId userId() {
        return SHARED.newUserId();
    }

    /**
     * Creates an endpoint id. Endpoint ids are not sharded.
     *
     * @return a new endpoint id
     */
    public static EndpointId endpointId() {
        return SHARED.newEndpointId();
    }

    /**
     * Creates a job id on the given shard.
     *
     * @param shard the shard, 0 to 255
     * @return a new job id
     * @throws IllegalArgumentException if {@code shard} is out of range
     */
    public static JobId jobId(int shard) {
        return SHARED.newJobId(Shard.of(shard));
    }

    /**
     * Creates a workflow id on the given shard.
     *
     * @param shard the shard, 0 to 255
     * @return a new workflow id
     * @throws IllegalArgumentException if {@code shard} is out of range
     */
    public static WorkflowId workflowId(int shard) {
        return SHARED.newWorkflowId(Shard.of(shard));
    }

    /**
     * Creates a run id on the given shard.
     *
     * @param shard the shard, 0 to 255
     * @return a new run id
     * @throws IllegalArgumentException if {@code shard} is out of range
     */
    public static RunId runId(int shard) {
        return SHARED.newRunId(Shard.of(shard));
    }

    /**
     * Creates a workflow-run id on the given shard.
     *
     * @param shard the shard, 0 to 255
     * @return a new workflow-run id
     * @throws IllegalArgumentException if {@code shard} is out of range
     */
    public static WorkflowRunId workflowRunId(int shard) {
        return SHARED.newWorkflowRunId(Shard.of(shard));
    }

    /**
     * Creates a dead-letter id on the given shard.
     *
     * @param shard the shard, 0 to 255
     * @return a new dead-letter id
     * @throws IllegalArgumentException if {@code shard} is out of range
     */
    public static DlqId dlqId(int shard) {
        return SHARED.newDlqId(Shard.of(shard));
    }
}
