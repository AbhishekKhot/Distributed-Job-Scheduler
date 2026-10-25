package dev.kairos.engine.run;

import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.template.Template;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Builds the versioned public/encrypted input envelope shared by progression, retries and recovery. */
public final class WorkflowInputs {
    private WorkflowInputs() {}

    /**
     * Reads only outputs referenced by a task's immutable request template. Sensitive values remain encrypted with
     * the original workflow-run/output associated data; public JSON types are preserved.
     * @param jdbc owning cell client, within the caller's transaction
     * @param team owning team
     * @param workflowRun owning workflow occurrence
     * @param request the task's request template
     * @param json JSON codec
     * @return the v1 input envelope, without plaintext sensitive values
     */
    public static byte[] load(JdbcClient jdbc, TeamId team, UUID workflowRun, JsonNode request, JsonMapper json) {
        Map<String, Set<String>> needed = new LinkedHashMap<>();
        collect(request, needed);
        Map<String, Map<String, JsonNode>> publicTasks = new LinkedHashMap<>(), sealedTasks = new LinkedHashMap<>();
        if (!needed.isEmpty())
            jdbc.sql("""
                SELECT task_key,outputs::text,sealed FROM scheduler.task_outputs
                WHERE team_id=:team AND workflow_run_id=:run AND task_key IN (:tasks)
                """)
                    .param("team", team.value())
                    .param("run", workflowRun)
                    .param("tasks", needed.keySet())
                    .query((rs, n) -> {
                        String key = rs.getString(1);
                        Set<String> names = java.util.Objects.requireNonNull(needed.get(key));
                        Map<String, JsonNode> plain = new LinkedHashMap<>(), sealed = new LinkedHashMap<>();
                        String raw = rs.getString(2);
                        if (raw != null)
                            json.readTree(raw).properties().forEach(e -> {
                                if (names.contains(e.getKey())) plain.put(e.getKey(), e.getValue());
                            });
                        byte[] bytes = rs.getBytes(3);
                        if (bytes != null && bytes.length > 0)
                            json.readTree(bytes).path("values").properties().forEach(e -> {
                                if (names.contains(e.getKey())) sealed.put(e.getKey(), e.getValue());
                            });
                        for (String name : plain.keySet())
                            if (sealed.containsKey(name)) throw new IllegalStateException("Duplicate workflow output");
                        if (!plain.isEmpty()) publicTasks.put(key, plain);
                        if (!sealed.isEmpty()) sealedTasks.put(key, sealed);
                        return 1;
                    })
                    .list();
        return json.writeValueAsBytes(Map.of("v", 1, "tasks", sealedTasks, "publicTasks", publicTasks));
    }

    private static void collect(JsonNode node, Map<String, Set<String>> needed) {
        if (node.isString()) {
            for (var reference : Template.compile(node.asString()).references())
                if (reference instanceof Template.OutputReference output)
                    needed.computeIfAbsent(output.task().value(), ignored -> new java.util.HashSet<>())
                            .add(output.output());
        } else if (node.isObject()) node.properties().forEach(e -> collect(e.getValue(), needed));
        else if (node.isArray()) node.forEach(child -> collect(child, needed));
    }
}
