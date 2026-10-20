package dev.kairos.domain.id;

import dev.kairos.domain.shard.Shard;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Creates Kairos ids (layout in {@link KairosId}).
 *
 * <p>Ids from one factory are strictly increasing: the 12-bit sequence field counts up within a
 * millisecond, starting from a random value below 2048 so that a burst of up to 2048 ids fits in
 * one millisecond. If the sequence runs out, or the clock moves backwards, the factory keeps
 * using the last timestamp it issued and moves it forward by one millisecond when needed, so
 * ordering never breaks. The random bits come from the injected {@link SecureRandom}.
 *
 * <p>Thread-safe: generation is guarded by a {@link ReentrantLock} (no {@code synchronized}, so
 * virtual threads are never pinned). One instance is shared per process.
 */
public final class KairosIdFactory {

    private static final long VERSION_BITS = 0x7000L;
    private static final long SEQUENCE_MASK = 0xFFFL;
    private static final int SEQUENCE_SEED_BOUND = 2048;
    private static final long VARIANT_BITS = 0x8000_0000_0000_0000L;
    private static final long RANDOM_MASK = 0x3FFF_FFFF_FFFF_FFFFL;
    private static final long SHARD_CLEAR_MASK = ~0xFFL;
    private static final long MAX_TIMESTAMP = (1L << 48) - 1;

    private final Clock clock;
    private final SecureRandom random;
    private final ReentrantLock lock = new ReentrantLock();

    private long lastMillis = -1;
    private long sequence;

    /**
     * Creates a factory.
     *
     * @param clock the time source for the timestamp field
     * @param random the CSPRNG for the sequence seed and random bits
     */
    public KairosIdFactory(Clock clock, SecureRandom random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Creates an id whose low 8 bits are random, for resources that are not sharded.
     *
     * @return a new UUIDv7
     */
    public UUID newId() {
        long leastSignificant;
        long mostSignificant;
        lock.lock();
        try {
            mostSignificant = nextMostSignificantBits();
            leastSignificant = random.nextLong();
        } finally {
            lock.unlock();
        }
        return new UUID(mostSignificant, VARIANT_BITS | (leastSignificant & RANDOM_MASK));
    }

    /**
     * Creates an id that carries a shard in its low 8 bits.
     *
     * @param shard the shard of the job or workflow the resource belongs to
     * @return a new UUIDv7 with {@code KairosId.shardOf(id).equals(shard)}
     */
    public UUID newId(Shard shard) {
        UUID id = newId();
        long leastSignificant = (id.getLeastSignificantBits() & SHARD_CLEAR_MASK) | shard.value();
        return new UUID(id.getMostSignificantBits(), leastSignificant);
    }

    /**
     * Creates a team id.
     *
     * @return a new team id
     */
    public TeamId newTeamId() {
        return new TeamId(newId());
    }

    /**
     * Creates a user id.
     *
     * @return a new user id
     */
    public UserId newUserId() {
        return new UserId(newId());
    }

    /**
     * Creates an endpoint id.
     *
     * @return a new endpoint id
     */
    public EndpointId newEndpointId() {
        return new EndpointId(newId());
    }

    /**
     * Creates a job id in the given shard.
     *
     * @param shard the job's shard, from {@link dev.kairos.domain.shard.ShardKey}
     * @return a new job id
     */
    public JobId newJobId(Shard shard) {
        return new JobId(newId(shard));
    }

    /**
     * Creates a workflow id in the given shard.
     *
     * @param shard the workflow's shard, from {@link dev.kairos.domain.shard.ShardKey}
     * @return a new workflow id
     */
    public WorkflowId newWorkflowId(Shard shard) {
        return new WorkflowId(newId(shard));
    }

    /**
     * Creates a run id in the shard of its job or workflow.
     *
     * @param shard the shard inherited from the job or workflow
     * @return a new run id
     */
    public RunId newRunId(Shard shard) {
        return new RunId(newId(shard));
    }

    /**
     * Creates a workflow-run id in the shard of its workflow.
     *
     * @param shard the shard inherited from the workflow
     * @return a new workflow-run id
     */
    public WorkflowRunId newWorkflowRunId(Shard shard) {
        return new WorkflowRunId(newId(shard));
    }

    /**
     * Creates a DLQ entry id in the shard of the dead-lettered run.
     *
     * @param shard the run's shard
     * @return a new DLQ id
     */
    public DlqId newDlqId(Shard shard) {
        return new DlqId(newId(shard));
    }

    private long nextMostSignificantBits() {
        long now = clock.millis();
        if (now > lastMillis) {
            lastMillis = now;
            sequence = random.nextInt(SEQUENCE_SEED_BOUND);
        } else if (sequence < SEQUENCE_MASK) {
            // Same millisecond, or the clock moved backwards: keep the last timestamp so ids
            // stay ordered.
            sequence++;
        } else {
            lastMillis++;
            sequence = random.nextInt(SEQUENCE_SEED_BOUND);
        }
        if (lastMillis < 0 || lastMillis > MAX_TIMESTAMP) {
            throw new IllegalStateException("Clock is outside the UUIDv7 timestamp range");
        }
        return (lastMillis << 16) | VERSION_BITS | sequence;
    }
}
