package dev.kairos.engine.run;

import dev.kairos.domain.id.KairosId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.model.TargetType;
import dev.kairos.domain.model.Trigger;
import dev.kairos.persistence.cell.Cell;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Creates a workflow occurrence and all its task runs in the caller's cell transaction. */
public final class WorkflowRunCreator {
    private final RunFactory factory;
    private final RunCreator creator;

    /**
     * Connects the shared task planner and transactional run creator.
     * @param factory plans task rows
     * @param creator inserts rows and dispatch outbox messages
     */
    public WorkflowRunCreator(RunFactory factory, RunCreator creator) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.creator = Objects.requireNonNull(creator, "creator");
    }

    /**
     * Inserts an occurrence once, with queued roots and waiting dependent tasks.
     * @param cell owning cell, with its transaction already open
     * @param team owning team
     * @param definition workflow definition id
     * @param version immutable definition version
     * @param run workflow run id, in the definition's shard
     * @param scheduledAt occurrence time
     * @param trigger schedule, immediate creation or manual execution
     * @param spread scheduled root dispatch offset
     * @param tasks pinned task definitions and dependency counts
     * @return false when the occurrence already exists
     */
    public boolean create(
            Cell cell,
            TeamId team,
            UUID definition,
            int version,
            UUID run,
            Instant scheduledAt,
            Trigger trigger,
            Duration spread,
            List<Task> tasks) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(
                        cell.database().dataSource())) {
            throw new IllegalStateException("Workflow creation requires the owning cell transaction");
        }
        if (tasks.isEmpty() || tasks.size() > 200) throw new IllegalArgumentException("A workflow has 1 to 200 tasks");
        if (trigger != Trigger.SCHEDULE && trigger != Trigger.IMMEDIATE && trigger != Trigger.MANUAL) {
            throw new IllegalArgumentException("Unsupported workflow creation trigger");
        }
        java.util.Set<dev.kairos.domain.model.TaskKey> keys = new java.util.HashSet<>();
        for (Task task : tasks) {
            if (!keys.add(Objects.requireNonNull(task.target().taskKey())))
                throw new IllegalArgumentException("Duplicate workflow task");
            RunTarget target = task.target();
            if (target.targetType() != TargetType.WORKFLOW_TASK
                    || !target.targetId().equals(run)
                    || !target.definitionId().equals(definition)
                    || target.definitionVersion() != version
                    || !target.teamId().equals(team))
                throw new IllegalArgumentException("Task does not belong to the occurrence");
        }
        String pins = "{"
                + String.join(
                        ",",
                        tasks.stream()
                                .map(task -> "\""
                                        + Objects.requireNonNull(task.target().taskKey())
                                                .value()
                                        + "\":{\"endpointId\":\""
                                        + task.target().endpointId().value() + "\",\"version\":"
                                        + task.target().endpointVersion() + "}")
                                .toList())
                + "}";
        int inserted = cell.jdbc()
                .sql("""
                INSERT INTO scheduler.workflow_runs(workflow_run_id,workflow_id,workflow_version,team_id,
                    shard_id,scheduled_at,trigger,status,total,task_pins)
                VALUES (:run,:definition,:version,:team,:shard,:scheduled,:trigger,1,:total,CAST(:pins AS jsonb))
                ON CONFLICT(workflow_id,workflow_version,scheduled_at) DO NOTHING
                """)
                .param("run", run)
                .param("definition", definition)
                .param("version", version)
                .param("team", team.value())
                .param("shard", KairosId.shardOf(run).value())
                .param("scheduled", OffsetDateTime.ofInstant(scheduledAt, ZoneOffset.UTC))
                .param("pins", pins)
                .param("trigger", trigger.code())
                .param("total", tasks.size())
                .update();
        if (inserted == 0) return false;
        List<PlannedRun> planned = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            planned.add(
                    task.remainingDependencies() > 0
                            ? factory.waiting(task.target(), scheduledAt, trigger, task.remainingDependencies())
                            : switch (trigger) {
                                case SCHEDULE -> factory.scheduled(task.target(), scheduledAt, spread);
                                case IMMEDIATE -> factory.immediate(task.target(), scheduledAt);
                                case MANUAL -> factory.manual(task.target(), scheduledAt);
                                default -> throw new IllegalArgumentException("Unsupported workflow trigger");
                            });
        }
        creator.create(planned, null);
        return true;
    }

    /**
     * A pinned task with its initial unfinished-parent count.
     * @param target the immutable call target
     * @param remainingDependencies number of direct parents, zero for a root
     */
    public record Task(RunTarget target, int remainingDependencies) {
        /** Validates the initial dependency count. */
        public Task {
            Objects.requireNonNull(target, "target");
            if (remainingDependencies < 0 || remainingDependencies > 199)
                throw new IllegalArgumentException("Invalid dependencies");
        }
    }
}
